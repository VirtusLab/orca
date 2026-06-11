# 0016. `ToolSet` capability axis and planner read-only network access

Status: Accepted · Date: 2026-06-11
Related: [ADR 0003](0003-pluggable-llm-backends.md) (backend surface), [ADR 0011](0011-reviewer-roster.md) (reviewers run read-only)

## Context

Planning turns run autonomously (stdin closed, no `ask_user` MCP) and
read-only. On every backend the read-only mode also blocks the network the
planner needs to read an issue/PR it was pointed at: claude's
`--permission-mode plan` prompts for `WebFetch`/`gh` (and an autonomous turn
can't answer), codex's `--sandbox read-only` blocks all network, pi's read-only
`--tools` has no web tool, gemini's `--approval-mode plan` gates web/shell.

Capability was previously encoded as a boolean `LlmConfig.readOnly` layered over
the `AutoApprove` enum, munged together in each backend's args mapping. Two
problems: (1) the boolean couldn't express "read-only **plus** network"; (2)
`withReadOnly` is the shared hard no-edit gate for *seven* turn kinds (two
planners, plan-review, brief, triage, code reviewers, reviewer-selection /
lint-summary), and `Reviewers.scala` relies on it so reviewers can't edit
mid-review — so network must not be tied to "read-only" in general.

## Decision

Replace `readOnly: Boolean` with a capability enum on `LlmConfig`:

```scala
enum ToolSet: case ReadOnly, NetworkOnly, Full
```

`ToolSet` is the **capability axis** (which tools exist); `AutoApprove` stays
the orthogonal **prompting axis** (which available tools auto-approve, only
meaningful interactively and consulted only on `Full`). Only the two autonomous
planner entry points (`Plan.autonomousResult` → `from`/`assessThenPlan`/`triage`)
select `NetworkOnly`; reviewers, `reviewed`/`briefed`, selection and lint keep
`ReadOnly`, hard everywhere.

### Per-backend `NetworkOnly` mapping

| Backend | `NetworkOnly` | No-edit guarantee | Network |
| --- | --- | --- | --- |
| claude | `plan` + `--allowedTools <networkTools>` | **hard** (command-scoped allowlist; plan mode blocks general bash + edits) | web + scoped `gh` |
| pi | `--tools …,bash` | **prompt-only** (bash permits writes) | shell (`gh`/`curl`) |
| codex | `--full-auto` + `-c sandbox_workspace_write.network_access=true` | **prompt-only** (workspace-write permits writes) | shell + web |
| gemini | `--approval-mode plan --allowed-tools web_fetch` | hard | web |
| opencode | write tools disabled (= `ReadOnly`) | hard | web only, server-dependent |

pi and codex have no read-only-with-network mode, so granting network forces a
writable surface; there the no-edit guarantee rests on the planner prompts
(`planning.md` / `assess-then-plan.md` / `triage.md` all forbid edits), not the
sandbox. **Verified** on the gemini CLI: plain `plan` mode blocks `web_fetch`,
but `plan` + `--allowed-tools web_fetch` runs it (returns content), so gemini
keeps its hard no-edit guarantee *and* gets web reads (no shell `gh`).
`--allowed-tools` is deprecated (gemini 1.0 → Policy Engine); migrate then.
opencode keeps `bash` off (no writable-shell network); its web tool isn't in the
disabled set, so web may work (server-dependent, unverified).

### Claude allowlist placement

The claude network allowlist (`--allowedTools` strings like `Bash(gh api:*)`) is
claude-specific, so it lives on `ClaudeBackend` (default
`DefaultNetworkTools`), not the shared `LlmConfig`. It is configurable per flow
via `claude.withNetworkTools(...)`. The default includes `Bash(gh api:*)` for
broad GitHub reads — note `gh api -X POST` can mutate GitHub (not local files);
flows wanting a tighter set override it.

## Consequences

- Claude planners get scoped read-only network with the hard no-edit guarantee
  intact; pi/codex planners get network with a prompt-only guarantee;
  gemini/opencode planners stay network-free and rely on pre-fetching.
- `withReadOnly` semantics are unchanged for the six non-planner turn kinds.
- `AutoApprove.Only` remains unused by flows (latent); not removed here.
