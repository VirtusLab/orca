> Final. Skeptic-amended: the event emits at the AGENT layer (the backend
> commit door lacks agent/role context) carrying `persistableWireId`; the
> manifest is per-run files under `.orca/cache/runs/` (pruned), written
> atomically on every session event with `outcome: "running"` until the run
> ends; gemini interactive resume is by `--list-sessions` index, not UUID.
> Where the body and `## Skeptic review` disagree, the skeptic section is
> authoritative.

# 08 — Session tracking & continuation

Topic 8 of the [research plan](00-research-plan.md): after a flow completes,
the shell offers "continue a session started in the flow", launching the
harness's own interactive UI resumed on the session's persistent ID.

## A. How Orca tracks sessions today

### A.1 The session model

Two id layers, both in `tools/src/main/scala/orca/agents/BackendTag.scala`:

- `SessionId[B]` (opaque, line 32) — Orca's stable *client* id, a UUID minted
  by `SessionId.fresh` (line 72). Every autonomous call has one: one-shot
  `agent.run` mints a throwaway per call
  (`tools/src/main/scala/orca/agents/Agent.scala:59`,
  `tools/src/main/scala/orca/agents/AgentCall.scala:34`).
- `WireSessionId[B]` (line 84) — the id the harness itself understands (what
  `--resume` takes). Learned per backend, mapped from client id by
  `SessionSupport` (`tools/src/main/scala/orca/backend/SessionSupport.scala`):
  the map commits via `commitAfterDrain` at
  `tools/src/main/scala/orca/backend/Conversations.scala:161` (autonomous) or
  `register` (interactive path). `IdScheme` (SessionSupport.scala:27–38) says
  whether the client id IS the wire id (`ClientClaimed`: claude, pi) or the
  server mints it (`ServerMinted`: codex, gemini, opencode).

Durable flow-level sessions are `FlowSession`
(`flow/src/main/scala/orca/Session.scala`): `agent.session(name, seed)`
(Session.scala:170) mints/reuses an id and records a `SessionRecord` in the
progress log; after each durable run `persistResumeWireId`
(Session.scala:314–329) writes the learned wire id back via
`ProgressStore.upsertSession`.

### A.2 Per-backend session-id capture (file:line)

| Backend | Scheme | Where the wire id is captured | Resume argv Orca already uses |
|---|---|---|---|
| claude | ClientClaimed | Wire id = Orca's own UUID, claimed at spawn with `--session-id` (`claude/src/main/scala/orca/tools/claude/ClaudeArgs.scala:60–64`). The CLI echoes it in the `result` message: `ClaudeConversation.scala:184` (`settleSuccess(wireId = sid, …)`, sid from `InboundMessage.Result`, parsed at `claude/src/main/scala/orca/tools/claude/streamjson/InboundMessage.scala:83`). `system.init`'s session id is *discarded* (`ClaudeConversation.scala:87` — pattern binds `_`). | `--resume <uuid>` (`ClaudeArgs.scala:72`) |
| codex | ServerMinted | `thread.started` event → `threadId` (`codex/src/main/scala/orca/tools/codex/CodexConversation.scala:119–120`), committed as `wireId` at `CodexConversation.scala:213`. | `codex exec resume --json <id>` (`codex/src/main/scala/orca/tools/codex/CodexArgs.scala:72–79`) |
| gemini | ServerMinted | stream-json `init` event's required `session_id` (`gemini/src/main/scala/orca/tools/gemini/jsonl/InboundEvent.scala:33,126`) captured at `gemini/src/main/scala/orca/tools/gemini/GeminiConversation.scala:86–87`, committed at `GeminiConversation.scala:123`. | `gemini --resume <id> … -p <prompt>` (`gemini/src/main/scala/orca/tools/gemini/GeminiArgs.scala:41–50`) |
| opencode | ServerMinted | `POST /session` response `SessionCreated.id` (`ses_…`) at `opencode/src/main/scala/orca/tools/opencode/OpencodeBackend.scala:163–171` (`serverSessionFor`). Sessions live in opencode's *global* on-disk store shared by every `opencode serve` on the machine (`opencode/src/main/scala/orca/tools/opencode/OpencodeServer.scala:35–41`), hence durable + probed via `GET /session/<id>` (`OpencodeBackend.scala:119–124`). | server API only (`/session/<id>/prompt_async`, `OpencodeBackend.scala:231`) |
| pi | ClientClaimed, **ephemeral** | No wire id at all: `SessionSupport.ephemeral` (`pi/src/main/scala/orca/tools/pi/PiBackend.scala:59–60`); sessions live in `os.temp.dir(prefix = "orca-pi-sessions-", deleteOnExit = true)` (`PiBackend.scala:51–52`), one subdir per client id, resumed within-run by `--continue` (`pi/src/main/scala/orca/tools/pi/PiArgs.scala:32–33`). `persistableWireId` always `None` → `SessionRecord.resumeWireId` stays `None` (`flow/src/main/scala/orca/progress/ProgressLog.scala:45–46`). | `pi --mode rpc --session-dir <dir> --continue` (in-run only) |

All backends spawn the CLI with `cwd = workDir` (e.g. `PiBackend.scala:145`
`cli.spawnPiped(args, cwd = workDir, …)`; codex additionally passes `-C`,
`CodexArgs.scala:114`). This matters for resume — see B.

### A.3 Events model

`orca.events.OrcaEvent` (`tools/src/main/scala/orca/events/OrcaEvent.scala`)
has **no event carrying a session id**. The full vocabulary is `StageStarted`,
`StageCompleted`, `ToolUse`, `Step`, `TokensUsed`, `StructuredResult`,
`UserPrompt`, `AssistantMessage`, `Error` — session identity appears in none of
them (`TokensUsed` carries only agent name/model/role strings).

A listener *can* be attached today: `flow(...)` takes
`extraListeners: List[OrcaListener]`
(`runner/src/main/scala/orca/flow.scala:112`), fanned out through
`EventDispatcher` (`runner/src/main/scala/orca/flow.scala:241–244`). But with
no session event, a collecting listener has nothing to collect — **a new event
is needed**, e.g.:

```scala
case SessionStarted(
    backend: String,        // BackendTag.wireName
    wireId: String,         // what the harness's own resume takes
    label: Option[String]   // FlowSession name, or enclosing stage/role
)
```

Natural emission points: `Conversations.runAutonomous`'s commit site
(`Conversations.scala:161`, which already holds `events: OrcaListener` and the
just-validated wire id) plus the interactive `register` path — emitting at the
commit door covers *every* session (one-shot and durable), not just
`agent.session(...)` ones. Dedup on `(backend, wireId)` at the listener.
*[amended — Skeptic §3]*

### A.4 Persistence today, and where a run manifest lives

- ADR 0013 (persistent plans) is **superseded by ADR 0018**; the progress log
  is now the universal resume mechanism.
- What persists session ids today: only `SessionRecord`s inside
  `.orca/progress-<promptHash>.json`
  (`flow/src/main/scala/orca/progress/ProgressStore.scala:65–75`,
  `ProgressLog.scala:63–70`) — fields `name`, `occurrence`, `id`, `seed`,
  `resumeWireId`, `backend`. Rehydrated on resume by
  `FlowLifecycle.rehydrateSessions`
  (`runner/src/main/scala/orca/runner/FlowLifecycle.scala:127`).

The progress log is *almost* a manifest but wrong for the shell's purpose:

1. It only records durable `agent.session(...)` sessions — one-shot
   `agent.run` / reviewer sessions never touch it.
2. Its filename needs the user prompt (`progress-<SHA-256(prompt)[0:12]>.json`)
   — the shell would have to glob and guess which run was "the last one".
3. It carries no flow name, timestamp, or completion status, and it is
   *committed to git* by stage commits (ProgressStore.scala:18–20) — a
   shell-consumption manifest is ephemeral machine state that must not land in
   history.
4. `resumeWireId` is only written after durable runs; pi's is always absent.

**Proposed home: `.orca/cache/runs/last-run.json`** *[amended — Skeptic §4:
per-run files]* (or `runs/<ISO-timestamp>.json` keeping the last N). `.orca/cache/` already exists
for exactly this class of data — self-gitignored + `CACHEDIR.TAG`
(`tools/src/main/scala/orca/OrcaDir.scala`, ADR 0019) — so nothing new is
committed and no `.gitignore` coordination is needed. Shape:

```json
{
  "flow": "review-pr.sc",
  "workDir": "/abs/path",
  "startedAt": "2026-07-18T10:12:00Z",
  "finishedAt": "2026-07-18T10:40:31Z",
  "outcome": "success",
  "sessions": [
    { "harness": "claude-code", "wireId": "6f0f…", "label": "implement",
      "resumable": true },
    { "harness": "pi", "wireId": null, "label": "run", "resumable": false,
      "reason": "pi sessions are deleted when the run's temp dir is reclaimed" }
  ]
}
```

`harness` spelling illustrative — the implementation records
`BackendTag.wireName`.

**Why file-based rather than listener-only:** topic 2 resolved flow
execution to a subprocess (which makes the file channel mandatory) rather
than in-process. An in-process flow could hand
sessions to the shell through a collecting `OrcaListener` in memory; a
subprocess flow cannot — the listener lives in the child JVM. A manifest file
written by a runner-side listener (fed by the new `SessionStarted` event and
finalized in `flow()`'s `finally`) works identically in both worlds: in-process
the shell just reads the file it knows the child wrote; out-of-process the file
is the only channel. So design for the file from the start; the in-process case
degenerates to "read the file you just produced" (optionally short-circuited by
the same listener object). The `writeLog` temp-file + atomic-move pattern
(ProgressStore.scala:153–177) should be reused for the manifest write.

## B. Per-harness interactive resume

### claude — supported, high confidence

- Command: `claude --resume <session-id>` (also `claude --continue` for the
  most recent session in the directory). Docs:
  https://code.claude.com/docs/en/sessions
- **`-p`/stream-json sessions resume interactively** — the docs state it
  explicitly: "Sessions created with `claude -p` or the Agent SDK do not appear
  in the session picker, but you can still resume one by passing its session ID
  to `claude --resume <session-id>`."
- **cwd matters — confirmed**: "Run this from the directory the session was
  started in: session ID lookup is scoped to the current project directory and
  its git worktrees, so a session created elsewhere reports `No conversation
  found with session ID`." Transcripts live at
  `~/.claude/projects/<cwd-encoded>/<session-id>.jsonl`. The shell must chdir
  to the flow's `workDir` before exec.
- Orca's wire id is its own minted UUID (claimed via `--session-id`), already
  in `SessionRecord.resumeWireId` / the `SessionSupport` map.
- Caveats: resumed session restores model + permission mode, but
  `bypassPermissions` is never restored (good — interactive continuation gets
  normal prompting); flags like `--mcp-config`/`--append-system-prompt` are not
  restored, so the `ask_user` MCP bridge is simply absent — fine for a human
  takeover. Suppressing persistence is opt-in (`--no-session-persistence`),
  which Orca does not pass, so transcripts are written.
- Confidence: **high** (current official docs, explicit headless-resume note).

### codex — supported, medium-high confidence

- Command: `codex resume <session-id>` opens the TUI on that thread; also
  `codex resume` (picker, current-repo scoped), `codex resume --last`,
  `codex resume --all` (cross-directory). Docs:
  https://developers.openai.com/codex/cli/features ("codex resume — reopen a
  recent chat from the current repository, or search across local chats"),
  https://inventivehq.com/knowledge-base/openai/how-to-resume-sessions,
  https://www.codeagentswarm.com/en/guides/codex-cli-conversation-history
- ID/location: rollout JSONL files under `~/.codex/sessions/YYYY/MM/DD/
  rollout-<timestamp>-<uuid>.jsonl`; the id Orca captures from
  `thread.started` is exactly what `codex exec resume <id>` takes — Orca
  already exercises that path (`CodexArgs.execResume`), proving exec-created
  sessions produce resumable rollouts (codex "rejects resuming a session
  started with `--ephemeral`; the backend never passes `--ephemeral`" —
  CodexArgs.scala:63–64).
- cwd: the *picker* is repo-scoped but resume-by-id targets the rollout
  directly; running from `workDir` is still the right default (the resumed
  sandbox/approvals context references it).
- Open verification for the skeptic pass: that the interactive `codex resume
  <id>` (TUI) accepts an `exec`-created thread id as smoothly as `codex exec
  resume` does — sources strongly suggest yes (2026 changelog: "thread
  summaries, renames, resume, and fork paths work better through ThreadStore"),
  but it is not stated verbatim in official docs. Confidence: **medium-high**.

### opencode — supported, high confidence

- Command: `opencode --session <ses_…>` (short `-s`) opens the TUI on that
  session; `--continue`/`-c` for the last one; `--fork` to branch. Docs:
  https://opencode.ai/docs/cli/ (flags confirmed for the top-level TUI command,
  `opencode run`, and `opencode attach`).
- Storage sharing — confirmed on both sides: Orca's own driver comment
  documents that opencode "persists sessions to a global on-disk store shared
  by every `opencode serve` on the machine, so a fresh process resumes a
  session minted by a prior one" (`OpencodeServer.scala:35–41`) — the TUI is
  just another client of that store. The wire id (`ses_…`) is captured from
  `POST /session` and persisted via `resumeWireId`.
- cwd: sessions are associated with the project directory; launch the TUI from
  `workDir` so the session is in scope.
- Confidence: **high** (official CLI docs + Orca's probe already relies on the
  shared store).

### gemini — supported, medium confidence

- Command: `gemini --resume <session-uuid>` (also `-r`, `--resume` bare =
  latest, `--resume <index>`; in-TUI `/resume` browser). Docs:
  https://geminicli.com/docs/cli/session-management/,
  https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/session-management.md
- **cwd matters — confirmed**: sessions are stored under
  `~/.gemini/tmp/<project_hash>/chats/` where the hash derives from the project
  root; "sessions are project-specific — switching directories switches session
  history". Resume from `workDir`.
- Headless-created sessions: Orca already resumes them headlessly
  (`gemini --resume <id> -p …`, GeminiArgs.scala:37–50), and the id comes from
  the stream-json `init` event, so the sessions are in the store. Interactive
  `gemini --resume <that-id>` reads the same per-project chats store, so it
  should open them; official docs don't explicitly bless the
  headless→interactive crossover (cf.
  https://github.com/google-gemini/gemini-cli/issues/14435, which was about
  surfacing the id in headless JSON output at all). Confidence: **medium** —
  verify empirically during implementation.

### pi — not resumable today; feasible with a driver change

- Orca targets the pi coding agent (`pi --mode rpc`, README: https://pi.dev/,
  repo: https://github.com/badlogic/pi-mono). The tool itself *does* support
  interactive resume: `pi -c` (continue most recent), `pi -r` (session
  browser), `pi --session <path|id>`; sessions normally auto-save to
  `~/.pi/agent/sessions/` organized by working directory. Docs:
  https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/README.md,
  https://github.com/badlogic/pi-mono/blob/HEAD/packages/coding-agent/docs/session.md
  (repo since moved to `github.com/earendil-works/pi` / pi.dev — see 07's
  skeptic sources)
- But Orca redirects pi's sessions into a `deleteOnExit` temp dir
  (`PiBackend.scala:51–52`) precisely so they are ephemeral
  (`SessionSupport.ephemeral`, `persistableWireId` = `None`). Nothing survives
  the JVM (and even before exit the path is a private temp dir the shell
  doesn't know).
- To support continuation the pi driver would need to (a) place session dirs
  under `.orca/cache/pi-sessions/<clientId>/` instead of a temp dir, and
  (b) record the session *file path* as the manifest's resume handle; the
  shell would then exec `pi --session <path>` (or `pi -c` with
  `--session-dir`). That is a deliberate scope decision — until made, the
  shell greys pi sessions out.
- Confidence in "not resumable as shipped": **high** (it's Orca's own code);
  in the remediation sketch: medium (pi's `--session <path>` semantics with a
  custom `--session-dir` need a hands-on check; note upstream issue #320 about
  `--resume` + session-dir interactions).

### Summary table

| Harness | Shell exec (from `workDir`) | cwd-scoped? | Confidence |
|---|---|---|---|
| claude | `claude --resume <uuid>` | yes (project dir + worktrees) | high |
| codex | `codex resume <thread-id>` | picker yes; by-id no (rollout is global) | medium-high |
| opencode | `opencode --session <ses_…>` | project-associated; run from workDir | high |
| gemini | `gemini --resume <uuid>` *[amended — resume by index via `--list-sessions`; Skeptic §5]* | yes (`<project_hash>` of root dir) | medium |
| pi | — (greyed out) | n/a today | high (that it's unsupported) |

## C. Proposed shell-side UX

After a flow completes (and on the main menu as "Continue a session", reading
the newest manifest under `.orca/cache/runs/`):

```
Continue a session started by review-pr.sc (finished 12 min ago):
❯ claude   · implement        (resumes claude --resume 6f0f…)
  codex    · reviewer #2      (resumes codex resume 7f9f…)
  pi       · run              [unavailable: pi sessions are not persisted]
  ← back
```

- List entries as `harness · label`, label = `FlowSession` name when the
  session came from `agent.session(...)`, else the enclosing stage name (the
  manifest-writing listener sees `StageStarted`/`StageCompleted`, so it can
  stamp the current stage onto each `SessionStarted` it records) with the
  agent's `role` tag when present.
- The menu entry is **disabled when the manifest is absent or its `sessions`
  list has no resumable entry** (fresh checkout, flow crashed before any
  session committed, pi-only flow).
- Non-resumable entries are shown greyed out with a reason rather than hidden —
  the user should learn *why* their pi session can't be continued, not wonder
  where it went. Reasons come from the manifest (`resumable: false, reason`),
  stamped by the driver's capability (`persistableWireId` returning `None` ⇒
  not resumable).
- On selection: the shell suspends its own JLine terminal, sets cwd to the
  manifest's `workDir`, and execs the harness command in the foreground
  (inheriting stdio) — the harness's own interactive UI takes over; on exit the
  shell resumes its menu. Stale ids are the harness's problem to report
  (claude: "No conversation found…"); the shell just returns to the menu.
- Staleness guard: entries older than some horizon (or whose `workDir` no
  longer exists) are dropped from the list; claude's default transcript
  retention is 30 days (`cleanupPeriodDays`).

## D. Recommended mechanism (for the ADR)

> Superseded in part by the Skeptic review (§3–§4, authoritative): emission
> moves to the Agent layer with the amended event shape, and the single
> `last-run.json` becomes per-run files under `.orca/cache/runs/`.

1. Add `OrcaEvent.SessionStarted(backend, wireId, label)` emitted at the
   wire-id commit doors (`Conversations.scala:161` + interactive `register`).
2. Runner installs a manifest listener (always on, like `LoggingListener`)
   that collects these plus stage context and atomically writes
   `.orca/cache/runs/last-run.json` at flow end (and incrementally, so a crash
   still leaves partial sessions on disk).
3. Shell reads the manifest file — works whether the flow ran in-process or as
   a subprocess (works regardless of topic 2's outcome — now decided:
   subprocess); in-process it may *also* keep the
   listener reference for a zero-IO fast path, but the file is the contract.
4. pi resumability is a separate follow-up (persistent session dirs under
   `.orca/cache/`), greyed out until done.

## Skeptic review

All five harness CLIs are installed on this machine (claude 2.1.214,
codex-cli 0.133.0, opencode 1.17.10, gemini 0.50.0, pi 0.80.2); resume-flag
claims were checked against `--help` output. Codebase citations were re-read at
the cited lines.

### 1. Per-claim verdicts (codebase)

| Claim | Verdict |
|---|---|
| `OrcaEvent` carries no session id | **Confirmed** — full vocabulary read (`tools/src/main/scala/orca/events/OrcaEvent.scala`); no variant carries one. |
| `SessionSupport` mapping, `IdScheme`, `commitAfterDrain`/`register`, ephemeral `persistableWireId = None` | **Confirmed** (`tools/src/main/scala/orca/backend/SessionSupport.scala`). Nuance: `commit` is `putIfAbsent` — later turns on the same client re-fire the commit door but never change the mapping, so the proposed dedup must be per `(harness, wireId)` and should be *last-write-wins on metadata* (see amended schema: `lastActiveAt`). |
| claude capture at `ClaudeConversation.scala:184`; `system.init` id discarded at :87 | **Confirmed** (`settleSuccess(` at :183, `wireId = sid` at :184; `SystemInit(_, model)` at :87). |
| codex capture at `CodexConversation.scala:119–120`, commit at :213 | **Confirmed** (`ThreadStarted(threadId, …)` → `sessionId = threadId` at :119–120; `settleSuccess(` at :212, `wireId = sessionId` at :213). |
| gemini capture at `GeminiConversation.scala:86–87`, commit at :123 | **Confirmed** (`Init(sessionId, model)` at :86–88; `settleSuccess(` at :122). |
| opencode capture at `OpencodeBackend.scala:163–171`; global store (`OpencodeServer.scala:35–41`) | **Confirmed** verbatim. |
| pi ephemeral temp dirs (`PiBackend.scala:51–52, 59–60`) | **Confirmed** (`os.temp.dir(prefix = "orca-pi-sessions-", deleteOnExit = true)`; `SessionSupport.ephemeral`). |
| Progress-log critique (durable-only, prompt-hashed filename, committed to git) | **Confirmed** — `ProgressStore.path` scaladoc: "The stage commit force-adds this single path". |
| `.orca/cache` self-gitignored + `CACHEDIR.TAG` | **Confirmed** (`OrcaDir.ensureCache`). |

### 2. Scope: every one-shot IS a durable harness session — the draft undersells this

Verified: `Agent.run` / `AgentCall.run` mint `SessionId.fresh` per call
(`Agent.scala:59`, `AgentCall.scala:34` — confirmed) and route through the same
`drainAndCommit` door. Per backend: claude always spawns with `--session-id`
and the repo never passes `--no-session-persistence` (grep: zero hits); codex
`exec` never passes `--ephemeral` (`CodexArgs.scala:60–64`); gemini/opencode
sessions land in their stores unconditionally. **So one-shot reviewer runs are
real, resumable, meaningful sessions** ("ask the reviewer a follow-up") — the
draft's emission-covers-everything stance is right — **but so are the
runtime's internal cheap calls** (branch naming, commit-message summaries via
`cheapOneShot`). A manifest listing every haiku commit-message session is
noise.

Fortunate accident, verified: `quietTextTurn` (`BaseAgent.scala:117–129`)
bypasses `runWithSession` and calls `backend.runAutonomous` directly — so if
`SessionStarted` is emitted at the Agent layer (see §3), internal calls are
excluded automatically. If it is emitted at the commit door as drafted, they
are all included and the manifest needs an `internal` filter. Either way the
schema needs a `kind` field (`durable` / `oneShot` / `interactive`) so the
shell can group durable sessions first and collapse one-shots.

### 3. Label: the proposed emission point CANNOT produce it — event shape amended

The draft claims the commit door (`Conversations.scala:161`) "already holds
`events` and the just-validated wire id". True, but **that is all it holds**:
`drainAndCommit(conv, session, sessions, events)` has no agent name, no role,
no stage, not even the backend's `wireName` as a value (only the type
parameter). The drafted `SessionStarted(backend, wireId, label)` cannot be
constructed there.

Amendment — emit at the **Agent layer**, not the backend:

- Autonomous free-text: `BaseAgent.autonomous.runWithSession`
  (`BaseAgent.scala:96–110`) — has `name`, `role`, and `result.wireId` after
  `backend.runAutonomous` returns (which is *after* the commit, so timing is
  identical).
- Autonomous structured: `DefaultAgentCall.runAutonomousWithRetry`
  (`AgentCall.scala:213–227`) — has `agentName`, `agentRole`, `result`.
- Interactive: next to the existing `backend.sessions.register` at
  `AgentCall.scala:283`.

Amended event:

```scala
case SessionStarted(
    backend: String,          // BackendTag.wireName, in scope at these sites
    clientId: String,         // SessionId — join key for FlowSession names
    wireId: Option[String],   // agent.resumeWireId(session): None for pi ⇒ not resumable
    agent: String,            // Agent.name
    role: Option[String]      // Agent.role
)
```

Use `resumeWireId` (i.e. `persistableWireId`), **not** the raw drained
`result.wireId`: pi's drain also produces a wire id, but it is a claimed UUID
into a temp dir — `persistableWireId` correctly reports `None`, which directly
yields the manifest's `resumable: false`.

Two label sources the event still can't carry, resolved listener-side:

- **Stage**: stages are sequential on the flow thread (frame-stack
  `enterStage`/`exitStage`, `Flow.scala:40–49`), so the listener can maintain a
  stage stack from `StageStarted`/`StageCompleted` and stamp the top onto each
  `SessionStarted` — sound even for parallel agent forks *within* a stage
  (they all belong to the open stage). The draft's claim here survives, but
  note it needs a stack, not a scalar (nested stages).
- **FlowSession name**: known only in `Session.scala` (`session(name, seed)`,
  :170; `persistResumeWireId`, :314). No event needed: the listener joins
  `clientId` against `SessionRecord.id` in the progress log at write time
  (the log is upserted incrementally after each durable run, so the join works
  for incremental manifest writes too).

Also a naming nit: the event fires at end of first successful *turn*, not at
session start — `SessionCommitted` or `SessionResumable` would be honest.

### 4. Manifest: `last-run.json` rejected; amended schema

- **Single file loses history.** A shell session runs several flows; the user
  may want the flow before last. Use one file per run:
  `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`; shell lists newest
  first; writer prunes to the last 20 files (or 30 days — matching claude's
  default transcript retention) on init. Size is trivial (<10 KB each).
  `FlowLock.acquireWorkdir` (flow.scala) already serializes same-workDir
  flows, so there is at most one writer per `.orca` — no cross-process write
  races.
- **Version skew is real and the draft ignores it.** Topic 2 recommends flows
  as scala-cli subprocesses with version-pinned orca deps. A script pinning
  orca < the manifest-writing release writes *nothing*; a script pinning a
  *newer* orca may write a *newer* schema. Shell rules: manifest dir absent or
  empty ⇒ menu entry disabled with a hint ("flow ran with an orca version
  without session tracking"); `manifestVersion` greater than the shell
  understands ⇒ skip that file with a message. The ADR must state the minimum
  orca version and that `manifestVersion` is a hard gate, not advisory.
- **Write strategy (draft's §D.2 "incrementally" made concrete):** rewrite the
  whole file atomically (temp + `atomicMove`, reusing the
  `ProgressStore.writeLog` pattern as drafted) on *every* `SessionStarted` and
  on stage transitions; stamp `outcome: "running"` from the first write and
  finalize `outcome`/`finishedAt` in `flow()`'s `finally`. A SIGKILL leaves
  `"running"` + a dead `pid`; the shell treats stale-running (pid not alive)
  as crashed and **still offers its sessions** — that is the whole point of
  incremental writes. Known gap, acceptable: the commit door only fires after
  a clean drain, so a crash mid-*first*-turn records nothing even though
  claude's transcript may exist on disk (claude's id is known at spawn). Not
  worth a claude-only eager emission; document as a limitation.

```json
{
  "manifestVersion": 1,
  "orcaVersion": "0.0.17",
  "flow": "review-pr.sc",
  "workDir": "/abs/path",
  "pid": 12345,
  "startedAt": "2026-07-18T10:12:00Z",
  "finishedAt": null,
  "outcome": "running",
  "sessions": [
    {
      "harness": "claude-code",
      "wireId": "6f0f…",
      "resumable": true,
      "reason": null,
      "agent": "claude",
      "role": "reviewer",
      "stage": "review",
      "sessionName": null,
      "kind": "oneShot",
      "firstSeenAt": "2026-07-18T10:14:02Z",
      "lastActiveAt": "2026-07-18T10:14:02Z"
    },
    {
      "harness": "pi",
      "wireId": null,
      "resumable": false,
      "reason": "pi sessions are deleted when the run's temp dir is reclaimed",
      "agent": "pi",
      "role": null,
      "stage": "implement",
      "sessionName": "coder",
      "kind": "durable",
      "firstSeenAt": "2026-07-18T10:20:00Z",
      "lastActiveAt": "2026-07-18T10:31:12Z"
    }
  ]
}
```

Dedup on `(harness, wireId)`; re-commits from later turns update `stage`,
`lastActiveAt`, and `sessionName` (last-write-wins — for "continue where it
left off", the *latest* context is the useful label, not the first).

### 5. Resume commands — corrected confidence (verified against installed CLIs)

| Harness | Local `--help` evidence (version) | Confidence |
|---|---|---|
| claude | `-r, --resume [value]`, `--session-id <uuid>`, `--fork-session`, `--no-session-persistence` all present (2.1.214) | **high** — stands |
| codex | `codex resume [SESSION_ID]` positional: "Conversation/session id (UUID) or thread name"; `--all`: "disables cwd filtering" ⇒ picker IS cwd-filtered by default, by-id is not — both cwd claims confirmed (0.133.0) | **medium-high** — argv shape now locally confirmed; the exec-created→TUI crossover remains empirically unverified (not testable via `--help`) |
| opencode | `-s, --session` ("session id to continue"), `-c, --continue`, `--fork` (1.17.10) | **high** — stands |
| gemini | `-r, --resume` documents **only** `"latest"` or an *index number* — the UUID form the draft leads with is absent from local help (0.50.0). `--list-sessions` "for the current project" confirms project scoping. | **medium, leaning down for interactive-by-UUID**. Orca's own `GeminiArgs.resume` passes the learned id headlessly, which is working evidence UUIDs are accepted on that path; interactive acceptance is unproven. Mitigation for the shell: run `gemini --list-sessions` from `workDir`, match the UUID, exec `--resume <index>`. |
| pi | `--session <path\|id>`, `--session-id <id>` ("exact project session ID, creating it if missing"), `--session-dir <dir>`, `-c`, `-r`, `--fork`, `--no-session` all present (0.80.2) | "not resumable as shipped": **high** — stands. Remediation sketch: **raised to medium-high** — every flag it needs exists in the installed version; only the custom `--session-dir` interaction needs a hands-on check. |

The cwd-scoping table in §B stands otherwise: all backends spawn with
`cwd = workDir` (confirmed, `PiBackend.scala:145` pattern + codex `-C` at
`CodexArgs` `cwdArgs`), and the shell must exec resume commands from the
manifest's `workDir` (which is why the manifest carries it).
