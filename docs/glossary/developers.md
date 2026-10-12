# Glossary for developers

This glossary defines Orca's internal vocabulary, which is used the same way in
identifiers, file names, screen output and prose. Only terms the
[user glossary](users.md) lacks are defined here; for the shared ones, this
page adds the codebase facts. The rest of the internals are described in
[AGENTS.md](https://github.com/VirtusLab/orca/blob/master/AGENTS.md).

## Review

The definitions are in the [user glossary](users.md#review); here is how they
map to the code.

- **finding** — A finding is a `ReviewFinding`, found in
  `ReviewResult.findings`. Note that `issue` is not a synonym: in this codebase
  it means a GitHub issue (`orca.tools.Issue`, `IssueHandle`).
- **declined** — A declined finding is a `DeclinedFinding`, found in
  `FixOutcome.declined`; it is one of several reasons a finding stays open.
  This is the wire shape the fixing agent fills, so it carries a title and a
  reason and nothing else.
- **open finding** — An open finding is an `OpenFinding`, found in
  `OpenFindings` and paired with an `OpenReason`. It is identified by its
  `FindingId`, never by its title. A review that could not run at all is
  `OpenFindings.skipped`, not an open finding.

Never name the open findings after one of the reasons. `OpenReason.describe` is
the only place where each reason's prose is written.

## Persisted state

Run and attempt are defined in the
[user glossary](users.md#flows-and-runs); here is what each one owns on disk.

- **run** — A run is keyed by `RunKey`, the 12-hex prefix of SHA-256(prompt).
  It owns one feature branch, one progress log
  (`.orca/runs/<key>.progress.json`), one event log
  (`.orca/cache/runs/<key>/events.jsonl`) and, under `--worktree`, one
  checkout. A successful run ends by deleting its progress log and appending
  `RunSucceeded` to its event log.
- **attempt** — An attempt is keyed by `AttemptId` (`<startedAt ms>-<pid>`).
  It owns its events in the run's event log and one trace log
  (`.orca/cache/runs/<key>/<id>.trace.log`). A fresh attempt starts a run; a
  resumed attempt continues one.

Never call a process a run. "Task" means only a plan task, and a plan task has
no file of its own.

## Backends

These are the words for talking to a coding agent, from the outside in.

- **call** — A call is one `agent.run`, `session.run` or `chat.run`. A retry
  stays inside the call. The review loop's fix turn is a call.
- **turn** — A turn is one exchange that reaches the model: a prompt sent,
  events streamed back, one outcome. A retry that reaches the model is a new
  turn. `AgentBackend.open` returns one as a `LiveTurn`, the in-flight turn.
- **message** — A message is one assistant message inside a turn. It is closed
  by `TurnEvent.AssistantMessageEnd` and shown as one
  `OrcaEvent.AssistantMessage`.
- **decoder** — A decoder is a backend's wire protocol as a `LineDecoder`: a
  fold over the lines of its stream. `DecodedTurn` runs the decoder over a
  turn.
- **conversation** — A conversation is the history a backend keeps across
  turns, which a session resumes. A `LiveTurn` is not a conversation.
- **client id** / **wire id** — The client id (`SessionId`) is Orca's own
  handle for a session, stable across attempts. The wire id (`WireSessionId`)
  is the id the backend knows the conversation by. `SessionId#onWire` is the
  only crossing between the two.
- **`IdScheme`** — The `IdScheme` says how wire ids come to be: `ClientClaimed`
  means the client id is the wire id (claude, pi), and `ServerMinted` means the
  backend mints it on the first turn (codex, gemini, opencode).
- **conversation key** — The conversation key (`OrcaEvent.conversationKey`) is
  the wire id, or the client id before one is known. It is the one key that
  turns and sessions join on, in events and in the event log.
- **dispatch** — A dispatch is `SessionSupport.dispatchFor`'s answer for the
  next turn: `Fresh` opens a conversation, `Resume` continues one.
  `ResumeOrigin` says whether this attempt or an earlier one opened it.
- **settle** — To settle is a decoder's `Step.Settle`: the turn's outcome is
  known and later lines are ignored. Only the decoder settles; `SessionSupport`
  only *confirms* a wire id restored from an earlier attempt.

Note that a CLI's own "turn" can differ from Orca's: codex's `turn.completed`
ends Orca's turn, but claude's `num_turns` counts tool calls plus one.

## Capabilities

The definitions are in the
[user glossary](users.md#capabilities-and-tool-limits); here are the threading
rules for each token.

- **`FlowContext`** — `FlowContext` is thread-safe, so forks receive it freely.
- **`FlowControl`** — `FlowControl` must stay on the thread that created it.
- **`InStage`** — `InStage` is the stage-bound token that may be shared: every
  agent run takes it, and a fork may capture it.
- **`WorkspaceWrite`** — `WorkspaceWrite` is the stage-bound token that may not
  be shared: every git, `gh`, `fs` and progress-log write takes it, and it must
  not cross a fork.
- **`RuntimeInStage`** — `RuntimeInStage` is the only way production code mints
  stage tokens outside a `stage(...)` body.
