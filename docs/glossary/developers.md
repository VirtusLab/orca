# Glossary for developers

Orca's internal vocabulary, used the same way in identifiers, file names,
screen output and prose. Only terms the [user glossary](users.md) lacks are
defined here; for the shared ones it adds the codebase facts. The rest of the
internals are in
[AGENTS.md](https://github.com/VirtusLab/orca/blob/master/AGENTS.md).

## Review

Definitions: [user glossary](users.md#review).

- **finding** is `ReviewFinding`, in `ReviewResult.findings`. `issue` is not a
  synonym: in this codebase it means a GitHub issue (`orca.tools.Issue`,
  `IssueHandle`).
- **declined** is `DeclinedFinding`, in `FixOutcome.declined`: one of several
  reasons a finding stays open. The wire shape the fixing agent fills, so it
  carries title and reason and nothing else.
- **open finding** is `OpenFinding`, in `OpenFindings`, paired with an
  `OpenReason`. Identified by its `FindingId`, never by its title. A review that
  could not run at all is `OpenFindings.skipped`, not an open finding.

Never name the open findings after one of the reasons. `OpenReason.describe` is
the only place each reason's prose is written.

## Persisted state

Definitions of run and attempt: [user glossary](users.md#flows-and-runs).

- **run** — keyed by `RunKey`, the 12-hex prefix of SHA-256(prompt). A run owns
  one feature branch, one progress log (`.orca/runs/<key>.progress.json`), one
  session-records file (`.orca/cache/runs/<key>.sessions.json`) and, under
  `--worktree`, one checkout. A successful run ends by deleting its progress
  log and session-records file.
- **attempt** — keyed by `AttemptId` (`<startedAt ms>-<pid>`). An attempt owns
  one manifest (`.orca/cache/attempts/<id>.manifest.json`) and one cost log
  (`<id>.cost.jsonl`). A fresh attempt starts a run; a resumed attempt
  continues one.

Never call a process a run. "Task" means only a plan task; a plan task has no
file of its own.

## Backends

Words for talking to a coding agent, from the outside in:

- **call** — one `agent.run` / `session.run` / `chat.run`. A retry stays inside
  the call. The review loop's fix turn is a call.
- **turn** — one exchange that reaches the model: a prompt sent, events
  streamed back, one outcome. A retry that reaches the model is a new turn.
  `AgentBackend.open` returns one as a `LiveTurn`, the in-flight turn.
- **message** — one assistant message inside a turn, closed by
  `TurnEvent.AssistantMessageEnd` and shown as one `OrcaEvent.AssistantMessage`.
- **decoder** — a backend's wire protocol as a `LineDecoder`: a fold over the
  lines of its stream. `DecodedTurn` runs the decoder over a turn.
- **conversation** — the history a backend keeps across turns, which a session
  resumes. A `LiveTurn` is not a conversation.
- **client id** (`SessionId`) — Orca's own handle for a session, stable across
  attempts. **wire id** (`WireSessionId`) — the id the backend knows the
  conversation by. `SessionId#onWire` is the only crossing.
- **`IdScheme`** — how wire ids come to be: `ClientClaimed` (the client id is
  the wire id: claude, pi) or `ServerMinted` (the backend mints it on the first
  turn: codex, gemini, opencode).
- **conversation key** (`OrcaEvent.conversationKey`) — the wire id, or the
  client id before one is known; the one key turns and sessions join on in
  events and the cost log.
- **dispatch** — `SessionSupport.dispatchFor`'s answer for the next turn:
  `Fresh` opens a conversation, `Resume` continues one. `ResumeOrigin` says
  whether this attempt or an earlier one opened it.
- **settle** — a decoder's `Step.Settle`: the turn's outcome is known and later
  lines are ignored. Only the decoder settles; `SessionSupport` only *confirms*
  a wire id restored from an earlier attempt.

A CLI's own "turn" can differ: codex's `turn.completed` ends Orca's turn, but
claude's `num_turns` counts tool calls plus one.

## Capabilities

Definitions: [user glossary](users.md#capabilities-and-tool-limits).

- **`FlowContext`** — thread-safe; forks receive it freely.
- **`FlowControl`** — must stay on the thread that created it.
- **`InStage`** — the stage-bound token that may be shared: every agent run
  takes it, and a fork may capture it.
- **`WorkspaceWrite`** — the stage-bound token that may not be shared: every
  git, `gh`, `fs` and progress-log write takes it, and it must not cross a
  fork.
- **`RuntimeInStage`** — the only way production code mints stage tokens
  outside a `stage(...)` body.
