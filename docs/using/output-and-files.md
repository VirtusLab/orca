# Terminal output and files

While Orca runs, the terminal is split into two zones: an **event log** that
grows top to bottom as stages and tools fire, and a **status line** pinned to
the bottom, showing the active stage breadcrumb with a spinner. Nested stages
are indented.

| Glyph | Meaning |
|---|---|
| `▶` | stage start, or a step: a single-line note like a branch switch |
| `▸` | the prompt sent to an agent |
| `●` | assistant prose |
| `⏺` | tool call, with the path, command or query in grey. A read-only call shows as a bare `⏺ read`, so a burst of them folds into one line; the [trace file](#files-under-orca) has the details |
| `⎿` | how many times the line above repeated, as `⎿ ×12` |
| `✖` | error |
| `?` | approval request, or a question for you; interactive turns only |
| `!` | caveat about a tool limit Orca cannot enforce for this run, see [Choosing agents](../authoring/choosing-agents.md); never indented under a stage |

Colours and animation turn off when stderr is not a terminal. `NO_COLOR=1`
forces colours off; `ORCA_NO_ANIMATION=1` suppresses the spinner.

## Closing summary

A finished run names the branch you are left on, the PR it opened if any, how
many files changed since the commit it started from, and the `git diff` that
shows them. Open review findings are listed too, see
[Pull requests](../authoring/pull-requests.md).

## Files under `.orca/`

`.orca/` holds committed configuration and machine-local state:

| Path | What | Committed |
|---|---|---|
| `.orca/settings.properties`, `.orca/reviewers/` | [settings](settings.md) and [custom reviewers](reviewers.md) | yes |
| `.orca/runs/<key>.progress.json` | a run's progress log, committed with each stage | yes |
| `.orca/cache/` | machine-local state; writes its own `.gitignore` | no |
| `.orca/worktrees/` | checkouts of [`--worktree` runs](run-lifecycle.md#worktrees) | no |

`<key>` is derived from the prompt, `<id>` from the attempt's start time and
pid. Under `.orca/cache/`:

| Path | Holds |
|---|---|
| `runs/<key>.sessions.json` | a run's durable session records |
| `attempts/<id>.manifest.json` | the attempt's sessions and status; what [`orca continue`](shell.md) lists |
| `attempts/<id>.cost.jsonl` | one line per agent turn: agent, role, model, stage, token usage and cost. The per-agent and per-model detail the closing summary leaves out |
| `attempts/<id>.trace.log` | a DEBUG trace: prompts, agent output, tool calls. Its path is printed at the start of a run. It rolls over at 4 MB |

The cache is safe to delete. Attempt files are pruned to the newest 20 that
recorded a session plus the newest 20 of any kind.

If your `.gitignore` covers all of `.orca/`, every attempt warns you to remove
that line so settings and progress logs can be committed. The cache stays
ignored regardless.
