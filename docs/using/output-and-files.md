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

- `runs/<key>.sessions.json`: a run's durable session records.
- `attempts/<id>.manifest.json`: the attempt's sessions and status. This is
  what [`orca continue`](shell.md) lists.
- `attempts/<id>.cost.jsonl`: one line per agent turn, with the agent, role,
  model, stage, token usage and cost. This is the per-agent and per-model
  detail that the closing summary leaves out.
- `attempts/<id>.trace.log`: a DEBUG trace with prompts, agent output and tool
  calls. Its path is printed at the start of a run, and it rolls over at 4 MB.

The cache is safe to delete. Attempt files are pruned to the newest 20 that
recorded a session, plus the newest 20 of any kind.

```{note}
If your `.gitignore` covers all of `.orca/`, every attempt warns you to remove
that line, so that settings and progress logs can be committed. The cache stays
ignored regardless.
```
