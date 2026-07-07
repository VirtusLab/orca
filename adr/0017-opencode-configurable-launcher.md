# 0017. Configurable OpenCode serve launcher

Status: Accepted · Date: 2026-06-13
Related: [ADR 0014](0014-opencode-server-driver.md) (OpenCode server driver), issue #10

## Context

ADR 0014 drives a shared `opencode serve` spawned as the hardcoded `opencode`
binary, with `--pure` deliberately omitted so the server reads the user's
config (`~/.config/opencode/opencode.json` + ambient env). Any provider declared
there — including a local Ollama provider — already works: orca just passes the
`provider/model` id through (`opencode.withModel("ollama", …)`).

Issue #10 asks for the *easy* path. The convenient way to wire Ollama is `ollama
launch opencode`, which injects Ollama's generated provider config inline via
`OPENCODE_CONFIG_CONTENT` (no hand-written `opencode.json`). Hand-writing that
config — npm package, baseURL, exact model ids, raised `num_ctx` for tool use —
is fiddly and error-prone, so the launcher is the genuine zero-config path. But
the `opencode` binary name was hardcoded, so the launcher couldn't be used.

## Decision

Make the serve launch command configurable via a public `OpencodeLauncher`,
threaded `flow(opencodeLauncher = …) → DefaultFlowContext → OpencodeBackend →
OpencodeServer → OpencodeArgs.serve`. orca appends `serve --port 0 --log-level
WARN` to the launcher prefix.

- `OpencodeLauncher.default` = `opencode` (unchanged behaviour).
- `OpencodeLauncher.ollama(model)` = `ollama launch opencode --model <model> --`.
- `OpencodeLauncher(Seq(...))` for any other wrapper.

`--model` is required for the Ollama launcher: empirically, `ollama launch`
otherwise falls back to interactive model selection, which fails in orca's
headless server context. The launcher injects config for, and sets as the server
**default**, exactly that one model — so a bare `opencode` turn (orca sends
`model = None`) routes to it with no `withModel`. The server declares only that
model and rejects any other Ollama id, so switching models means relaunching
with a different `ollama(...)`.

Teardown SIGINTs the process **tree** (`CliProcess.sendSigIntTree`, used only by
the OpenCode server), not just the spawned PID: a launch wrapper that forks the
real `opencode serve` would otherwise leave it orphaned.

This partially realises the "orca-supplied provider config" future-work note in
ADR 0014 — by delegating config generation to `ollama launch` rather than orca
writing `OPENCODE_CONFIG_CONTENT` itself. The latter (for non-Ollama providers,
or to avoid the `ollama` CLI dependency) remains open.

## Consequences

- Two documented ways to use a local Ollama model: the launcher (zero config,
  one pinned model, no `withModel`) or a manual `opencode.json` provider block +
  `withModel` (several models, per-turn switching). README covers both.
- New public surface: `OpencodeLauncher` and the `flow(opencodeLauncher = …)`
  parameter.
- Not verified end-to-end against a live model in this environment (CPU-only,
  Ollama model registry firewalled). Validated via unit tests and live
  inspection of a launcher-started server's injected `/config` (the `ollama`
  provider and default model are present); a real agentic turn was too slow to
  complete on a 0.5B model under OpenCode's system prompt. A real-model run on
  capable hardware is recommended before relying on it.
