# Run event log — specification

Issue [#290](https://github.com/VirtusLab/orca/issues/290) asks for a stable,
public way to read a run's cost. Today a run's cached state is spread over
per-run and per-attempt files, and nothing links an attempt to its run.
[ADR 0025](../../../adr/0025-run-event-log.md) replaces those files with one
event log per run. This specification describes the change.

## Files

    .orca/runs/<key>.progress.json                  committed, unchanged
    .orca/cache/runs/<key>/events.jsonl             the run's event log
    .orca/cache/runs/<key>/<attemptId>.trace.log    per-attempt trace (+ .trace.1.log)

Removed: `.orca/cache/runs/<key>.sessions.json`,
`.orca/cache/attempts/<id>.manifest.json`, `<id>.cost.jsonl`, and the
`attempts/` directory. Old files are not read or migrated: the first attempt
of the new version deletes a leftover `.orca/cache/attempts/` and
`.orca/cache/runs/*.sessions.json`.

A `--worktree` run writes inside its worktree, as today.

## Events

One JSON object per line. Every line has:

- `type`: the event's case name, e.g. `"Turn"` (the codebase's enum convention);
- `at`: an ISO-8601 instant;
- `attempt`: the `AttemptId`.

A `stage` field is always the full stage path, in the progress log's encoding:
a JSON array of `{"name", "occurrence"}` segments, outermost first. On
`SessionCommitted` and `Turn` it is the innermost open stage, absent outside
any stage. Fields marked `?` are optional (absent or `null`).

| `type` | Fields | Written when |
|---|---|---|
| `AttemptStarted` | `schema`, `orcaVersion`, `flow?`, `workDir`, `pid`, `trace?` (path of the trace log) | the attempt starts |
| `BranchBound` | `branch` | `OrcaEvent.BranchBound` |
| `StageStarted` | `stage` | `OrcaEvent.StageStarted` |
| `StageEnded` | `stage`, `outcome` (`Completed` / `Failed` / `Replayed`) | `OrcaEvent.StageEnded` |
| `SessionMinted` | `name`, `stage`, `id`, `seed`, `backend` | `agent.session(name, seed)` mints a durable session |
| `SessionWireId` | `id`, `wireId` | a durable session learns its resume wire id |
| `SessionCommitted` | `backend`, `wireId?`, `conversationKey`, `agent`, `role?`, `minted?` (`{name, stage}`), `stage?` | every `OrcaEvent.SessionCommitted`, repeats included |
| `Turn` | `agent`, `role?`, `model?`, `stage?`, `turn`, `apiCalls?`, `usage`, `cost?`, `conversationKey` (today's `CostRecord` without its own `at`) | `OrcaEvent.TokensUsed` |
| `RunSucceeded` | `branch`, `published?` (the PR/MR reference) | success teardown |
| `AttemptFinished` | `outcome` (`Succeeded` / `Failed`) | `flow()`'s `finally` |

A successful attempt writes `RunSucceeded` and then `AttemptFinished`.

`schema` starts at `1`. Within one schema number, changes are additive only:
new event types and new optional fields. Readers ignore unknown types and
fields.

## Writing

- At attempt start, the writer reads the event log (if present) and builds the
  session-store projection; the attempt needs nothing else from the file. A
  missing or unreadable file gives an empty projection.
- The projection is an immutable value. An actor owns it in a single `var`;
  every update and read is an `ask` on that actor, so reads see every earlier
  update.
- After updating the projection, the actor hands the event to a second actor
  with `tell`, which appends it to the file. Events reach the file in order, and
  the run does not wait for the write (only for mailbox space, when it is full).
- The run's session lookups (`Session.scala`: lookup by key, by id, and
  wire-id write-back) read the projection. `SessionStore` keeps `records()` and
  `upsert`; `upsert` no longer takes `WorkspaceWrite`, since the actor
  serialises updates. `path` and `discard()` are removed.
- Neither actor throws. Both log and swallow any failure, as today's writer
  does: an exception in a `tell` handler would fail the attempt, and one in an
  `ask` would disable the listener for the rest of the attempt.
- Appends are best-effort, as today's cost log: a failed append is logged and
  dropped. A crash may lose queued events. Lost session events cost a re-seed
  on resume, the documented fallback.
- Success teardown appends `RunSucceeded` instead of deleting the session
  records.
- `finish` (success and failure) appends `AttemptFinished`, then waits until
  the appender's queue is empty. Queued events are otherwise dropped when the
  attempt's scope ends.
- A failed run that is abandoned and started again as a fresh run reuses the
  abandoned run's sessions, as today: only `RunSucceeded` is a boundary.

## Reading

All readers skip a line that does not decode (a torn tail after a crash) and
events of unknown `type`.

Projections:

- **Session store** (the run): `SessionMinted` and `SessionWireId` after the
  last `RunSucceeded`, folded into the latest record per `SessionKey`.
- **Attempts** (the shell): per attempt, `AttemptStarted` + `BranchBound` +
  `AttemptFinished`; sessions from `SessionCommitted`, deduplicated by
  `(backend, conversationKey)`, in first-seen order (`SessionRef`'s position);
  a session's `stage` and last-active time come from its latest
  `SessionCommitted`. An attempt with no `AttemptFinished` and a dead `pid`
  crashed, as today.
- **Cost**: the sum of `Turn` lines; the basis and unpriced rules below.

## Shell

- **Menu redraw:** show the session count of the newest attempt (by
  `AttemptId`) that has sessions, across the work dir and its worktrees, as
  today. Go through the event logs newest first by modification time, reading
  only `AttemptStarted` and `SessionCommitted` lines, and keep the newest
  attempt with sessions found so far. Stop when the next file was last
  modified before that attempt started: nothing in it can be newer.
- **Continue selected:** read every event log and build the full listing.
- `ManifestReader` becomes an event-log reader. It produces the values
  `SessionIndex`, `SessionPicker`, `ResumeCommand` and `SessionRef` use today.
- `WorktreeScan` ranks worktrees by the modification time of their newest
  `.orca/cache/runs/*/events.jsonl`. A directory's own modification time
  changes only when an entry is added or removed, so it can't be used.

## Pruning

Pruning runs at attempt start and deletes whole run directories. It never
deletes:

- the current run's directory (its `events.jsonl` may not exist yet);
- a run with a `SessionMinted` after its last `RunSucceeded`. It may still be
  resumed, and today `sessions.json` is never pruned. The progress log can't
  be the check: it is on disk only while its branch is checked out.

Of the rest, it keeps the newest 20 run directories that recorded a session,
plus the newest 20 of any kind, by modification time of `events.jsonl`.

In the current run's directory only, it keeps the trace logs (rolled parts
included) of the newest 20 attempts, by the attempt id in the file name. This
also removes traces of attempts that died before `AttemptStarted`.

## Startup banner

`OrcaLog.start` and `OrcaBanner.print` take the `RunKey`, which is known
before either runs (`flowEntry.scala:158`).

    Orca <version>
      progress: <path to progress log>
      events:   <path to event log>
      trace:    <path to trace log>

## Documentation

- `docs/using/output-and-files.md`: the new layout, the event table and the
  public-format rule, with examples:

  ```bash
  # total cost of a run
  jq -s 'map(select(.type == "Turn") | .cost.amount // 0) | add' events.jsonl
  ```

  and the cost caveats: a `Turn` with `cost: null` was not priced and is not in
  the sum; a sum with any `Estimated` basis is an estimate.
- AGENTS.md: the persisted-state table and the run/attempt vocabulary.
- `docs/glossary/developers.md`: the run and attempt entries.
- ADR 0018 (the 2026-09-18 and 2026-09-22 amendments name the old paths) and
  ADR 0021: an amendment pointing to ADR 0025.
- Code comments naming the old files, e.g. `CostTracker.scala`,
  `GeminiBackend.scala`, `SessionStore.scala`, `AttemptManifest.scala`.
- Issue #290: a follow-up comment replacing the manifest + cost-file answer.

## Out of scope

- The committed progress log: unchanged.
- Trace log content and rolling.
- Reading cache files written by an older orca.
