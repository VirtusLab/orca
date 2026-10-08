# Terminal output and files

While Orca runs, the terminal is split into two zones. The **event log** grows
from top to bottom as stages and tools fire, and the **status line**, pinned to
the bottom, shows the active stage breadcrumb with a spinner. Nested stages are
indented.

Each line of the event log starts with a glyph that says what kind of event it
is:

- `▶` marks a stage start, or a step: a single-line note such as a branch
  switch.
- `▸` is the prompt sent to an agent.
- `●` is assistant prose.
- `⏺` is a tool call, with the path, command or query in grey. A read-only call
  shows as a bare `⏺ read`, so a burst of them folds into one line; the
  [trace file](#files-under-orca) has the details.
- `⎿` says how many times the line above repeated, as in `⎿ ×12`.
- `✖` is an error.
- `?` is an approval request, or a question for you. It appears in interactive
  turns only.
- `!` is a caveat about a tool limit that Orca cannot enforce for this run,
  see [Choosing agents](../authoring/choosing-agents.md). It is never indented
  under a stage.

Colours and animation are turned off when stderr is not a terminal. You can
also force colours off with `NO_COLOR=1`, and suppress the spinner with
`ORCA_NO_ANIMATION=1`.

## Closing summary

When a run finishes, it names the branch you are left on, the PR it opened if
any, how many files changed since the commit it started from, and the
`git diff` that shows them. Open review findings are listed too; see
[Pull requests](../authoring/pull-requests.md).

## Files under `.orca/`

The `.orca/` directory holds both committed configuration and machine-local
state:

| Path | What | Committed |
|---|---|---|
| `.orca/settings.properties`, `.orca/reviewers/` | [settings](settings.md) and [custom reviewers](reviewers.md) | yes |
| `.orca/runs/<key>.progress.json` | a run's progress log, committed with each stage | yes |
| `.orca/cache/` | machine-local state; writes its own `.gitignore` | no |
| `.orca/worktrees/` | checkouts of [`--worktree` runs](run-lifecycle.md#worktrees) | no |

In these paths, `<key>` is derived from the prompt and `<id>` from the
attempt's start time and pid. Under `.orca/cache/` you will find:

- `runs/<key>/events.jsonl`: the run's event log, described below. This is
  what [`orca continue`](shell.md) lists sessions from.
- `runs/<key>/<id>.trace.log`: a DEBUG trace with prompts, agent output and
  tool calls. It rolls over at 4 MB, to `<id>.trace.1.log`.

Each attempt starts by printing the paths of its run's progress log and event
log and its own trace log:

```text
Orca <version>
  progress: <path to progress log>
  events:   <path to event log>
  trace:    <path to trace log>
```

The cache is safe to delete. At the start of each attempt, run directories are
pruned to the newest 20 that recorded a session, plus the newest 20 of any
kind. A run with sessions recorded after its last success may still be resumed,
so it is never pruned. Each run directory keeps the trace logs of its newest 20
attempts.

### The event log

The event log is append-only, with one JSON object per line. A run of the same
prompt appends to the same file. Every line has:

- `type`: the event type, e.g. `"Turn"`;
- `at`: an ISO-8601 instant;
- `attempt`: the attempt's `<id>`.

A `stage` field is a stage path: a JSON array of `{"name", "occurrence"}`
segments, outermost first. On `SessionCommitted` and `Turn` it is the
innermost open stage, and is absent outside any stage. Fields marked `?` are
optional (absent or `null`).

| `type` | Fields | Written when |
|---|---|---|
| `AttemptStarted` | `schema`, `orcaVersion`, `flow?`, `workDir`, `pid`, `trace?` (path of the trace log) | the attempt starts |
| `BranchBound` | `branch` | the run's feature branch is known |
| `StageStarted` | `stage` | a stage starts |
| `StageEnded` | `stage`, `outcome` (`Completed` / `Failed` / `Replayed`) | a stage ends |
| `SessionMinted` | `name`, `stage`, `id`, `seed`, `backend` | `agent.session(name, seed)` creates a durable session |
| `SessionWireId` | `id`, `wireId` | a durable session learns the id its backend resumes it by |
| `SessionCommitted` | `backend`, `wireId?`, `conversationKey`, `agent`, `role?`, `minted?` (`{name, stage}`), `stage?` | after each agent turn, repeats included |
| `Turn` | `agent`, `role?`, `model?`, `stage?`, `turn`, `apiCalls?`, `usage`, `cost?`, `conversationKey` | an agent turn reports its token usage |
| `RunSucceeded` | `branch`, `published?` (the PR/MR reference) | the run succeeds |
| `AttemptFinished` | `outcome` (`Succeeded` / `Failed`) | the attempt ends |

A successful attempt writes `RunSucceeded` and then `AttemptFinished`. Only
sessions minted after the last `RunSucceeded` are resumed. An attempt without
`AttemptFinished` whose process is gone crashed.

The format is public. `schema` on `AttemptStarted` starts at `1`. Within one
schema number, changes are additive only: new event types and new optional
fields. Readers should ignore unknown types and fields, and skip a line that
does not parse (a crash can leave a torn last line).

For example, the total cost of a run:

```bash
jq -s 'map(select(.type == "Turn") | .cost.amount // 0) | add' events.jsonl
```

A `Turn` with `cost: null` was not priced and is not in the sum. If any
`Turn` has an `Estimated` cost basis, the sum is an estimate.

```{note}
If your `.gitignore` covers all of `.orca/`, every attempt warns you to remove
that line, so that settings and progress logs can be committed. The cache stays
ignored regardless.
```
