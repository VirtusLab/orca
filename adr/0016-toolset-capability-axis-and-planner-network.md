# 0016. `ToolSet` capability axis and planner read-only network access

Status: Accepted · Date: 2026-06-11
Related: [ADR 0003](0003-pluggable-llm-backends.md) (backend surface), [ADR 0011](0011-reviewer-roster.md) (reviewers run read-only)

## Context

Planning turns run autonomously (stdin closed, no `ask_user` MCP) and
read-only. On every backend the read-only mode also blocks the network the
planner needs to read an issue/PR it was pointed at: claude's read-only tier
withholds `WebFetch`/`WebSearch`, codex's `--sandbox read-only` blocks all
network, pi's read-only `--tools` has no web tool, gemini's `--approval-mode
plan` gates web/shell.

Capability was previously encoded as a boolean `AgentConfig.readOnly` layered over
the `AutoApprove` enum, munged together in each backend's args mapping. Two
problems: (1) the boolean couldn't express "read-only **plus** network"; (2)
`withReadOnly` is the shared hard no-edit gate for *seven* turn kinds (two
planners, plan-review, brief, triage, code reviewers, reviewer-selection /
lint-summary), and `Reviewers.scala` relies on it so reviewers can't edit
mid-review — so network must not be tied to "read-only" in general.

## Decision

Replace `readOnly: Boolean` with a capability enum on `AgentConfig`:

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
| claude | `--tools <read-only tools + networkTools>` | **hard** (`--tools` removes every unlisted built-in, shell and edits included) | web |
| pi | `--tools …,bash` | **prompt-only** (bash permits writes) | shell (`gh`/`curl`) |
| codex | `--full-auto` + `-c sandbox_workspace_write.network_access=true` | **prompt-only** (workspace-write permits writes) | shell + web |
| gemini | `--approval-mode plan --allowed-tools web_fetch` | **prompt-only** (plan mode unmeasured against a write) | web |
| opencode | write tools disabled (= `ReadOnly`) | hard | web only, server-dependent |

pi and codex have no read-only-with-network mode, so granting network forces a
writable surface; there the no-edit guarantee rests on the planner prompts
(`planning.md` / `assess-then-plan.md` / `triage.md` all forbid edits), not the
sandbox. **Verified** on the gemini CLI: plain `plan` mode blocks `web_fetch`,
but `plan` + `--allowed-tools web_fetch` runs it (returns content), so gemini
gets web reads (no shell `gh`). What that probe did *not* establish is gemini's
no-edit guarantee — see the amendment below.
`--allowed-tools` is deprecated (gemini 1.0 → Policy Engine); migrate then.
opencode keeps `bash` off (no writable-shell network); its web tool isn't in the
disabled set, so web may work (server-dependent, unverified).

### Claude allowlist placement

The claude network tool names are claude-specific, so they live on
`ClaudeBackend` (default `DefaultNetworkTools`), not the shared `AgentConfig`.
Configurable per flow via `claude.withNetworkTools(...)`.

Amended 2026-08-05 (#78, #84): claude's read-only tiers moved from
`--permission-mode plan` to a `--tools` allowlist, which is the capability
removal plan mode was assumed to be and was not. `--tools` takes bare tool
names, so the default's five command-scoped `Bash(gh …)` entries are gone;
measured planner use of `gh` was zero, and orca reads issues host-side via
`GitHubTool.readIssue`. The default is now `WebFetch`, `WebSearch`.

### gemini's no-edit guarantee

Amended 2026-08-05 (#78): gemini's read-only tiers drop from **hard** to
**prompt-only**. Nothing has measured `--approval-mode plan` against a write
attempt; the 2026-06 probe above only established that `--allowed-tools
web_fetch` re-enables web reads. claude's `--permission-mode plan` — the same
class of mechanism — turned out to remove no tools at all, and gemini also
downgrades `plan` to `default` in untrusted folders, which is where orca runs
agents. The cell records what orca can stand behind, not a known weakness;
raise it when a probe establishes more.

## Consequences

- Claude planners get read-only network with the hard no-edit guarantee intact;
  pi/codex/gemini planners get a prompt-only guarantee; opencode planners stay
  network-free and rely on pre-fetching.
- `withReadOnly` semantics are unchanged for the six non-planner turn kinds.
- `AutoApprove.Only` remains unused by flows (latent); not removed here.
