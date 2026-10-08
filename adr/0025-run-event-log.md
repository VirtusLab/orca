# 0025. One event log per run for cached state

Status: Proposed · Date: 2026-10-08
Related: [ADR 0018](0018-stage-bound-flow-runtime.md) (progress log; the
2026-09-18 session store amendment), [ADR 0019](0019-project-stack-settings.md)
(`.orca/` layout), [ADR 0021](0021-orca-shell.md) §8 (attempt manifest and cost
log). Issue [#290](https://github.com/VirtusLab/orca/issues/290).

## Context

A run writes one committed file and, in the cache, a set of files spread over
two keys:

- `.orca/runs/<key>.progress.json`: committed; what a resume needs.
- `.orca/cache/runs/<key>.sessions.json`: durable session records, read back by
  the run; deleted on success.
- `.orca/cache/attempts/<id>.manifest.json`: per attempt; read by the shell.
- `.orca/cache/attempts/<id>.cost.jsonl`: per attempt; read by nothing in orca.
- `.orca/cache/attempts/<id>.trace.log`: per attempt; read by people.

External tools want a run's cost and state (#290). Today they must find the
attempts of a run, but nothing records which run an attempt belongs to. The
formats are internal, and each file has its own lifecycle and pruning rule.

The split between committed and cached data is already right: every
progress-log field is needed to resume, and nothing in the cache is needed for a
correct resume (losing session records costs a re-seed, not correctness). This
ADR changes only the shape of the cache.

## Decision

All cached state of a run is one append-only JSONL event log:

    .orca/cache/runs/<key>/events.jsonl
    .orca/cache/runs/<key>/<attemptId>.trace.log   (+ .trace.1.log)

It replaces `sessions.json`, the attempt manifest and the cost log. Trace logs
stay as log files; the event log points to them.

- **Faithful record.** Events are written as they happen, repeats included
  (every `SessionCommitted`). Readers fold and deduplicate.
- **One writer per attempt, asynchronous.** The attempt keeps an in-memory
  projection of the log, loaded from the file at start (empty if absent). The
  projection is an immutable value held by an actor; the run reads it through
  that actor. A second actor appends events to the file in the background.
  Writes lost in a crash are acceptable: this is a cache.
- **Run boundary in the log.** A run of the same prompt reuses the key and the
  file. Success appends `RunSucceeded`; session lookup ignores everything before
  the last one. This replaces deleting `sessions.json` on success, with the same
  effect.
- **Stage events are recorded** in the cache too. They duplicate the progress
  log for a different reader: the progress log is for resuming, and is deleted
  on success; the event log is for observing a run, during and after.
- **Public format.** The event types and fields are documented. Every
  `AttemptStarted` carries `schema`; within one schema number, changes are
  additive only (new event types, new optional fields).
- **Startup banner** prints the paths of the progress log and the event log
  beside the trace log.

## Alternatives considered

- **Status quo plus a `run` field in the attempt manifest, with the manifest and
  cost formats made public.** The smallest change, and the first answer on
  #290. Rejected: tools still join two files per attempt and scan for a run's
  attempts, and three cache files keep three lifecycles. It also fixes two
  formats as public that this ADR would replace.
- **Cost summary in the attempt manifest.** Rejected: copies data the cost log
  already has, and brings back the mix of session and cost data that ADR 0021's
  2026-08-05 amendment split apart.
- **A new file per run instance (rotate on success).** Rejected: a failed
  rename mixes two runs, and readers scan two name patterns. A boundary event
  keeps one file per key.
- **Synchronous writes (an actor `ask` per event).** Rejected: the cache need
  not survive a crash intact, so the run should not wait on disk.

## Consequences

- One file per run, with the attempt-to-run link built in. A run's cost is one
  `jq` over one file.
- Readers replay instead of reading a snapshot, and must skip a torn last line.
- Pruning works on run directories. A run with session records after its last
  `RunSucceeded` may still be resumed and is never pruned (ADR 0018's
  2026-09-18 amendment rejected the manifest as the session store for exactly
  this risk).
- The session store's writes are no longer a read-modify-write of one file, so
  `upsert` drops `WorkspaceWrite`; the actor serialises updates.
- Queued appends are lost when the attempt's scope ends, so finishing an
  attempt waits for the appender's queue to empty.
- No compatibility with existing cache files is owed; old manifests stop being
  listed.
- Supersedes ADR 0021 §8's file layout (manifest and cost log) and the location
  part of ADR 0018's 2026-09-18 session store amendment.
