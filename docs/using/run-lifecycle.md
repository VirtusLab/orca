# Branches, resume and worktrees

A **run** is one prompt's flow execution, across however many processes it
takes to finish. An **attempt** is one of those processes: one `orca run`, or
one `flow(...)` call. An interrupted run is resumed by attempting it again with
the same prompt.

Each run is bound to exactly one feature branch and one progress log,
`.orca/runs/<key>.progress.json`, where `<key>` is derived from the prompt.
Which [stages](../glossary/users.md#flows-and-runs) finished, and with what
results, is recorded there.

## Start

On a fresh run Orca:

1. Asks what to do with uncommitted changes; stash is the default (see
   [Run targets](#run-targets)). Stashed changes come back with `git stash pop`.
2. Creates and checks out the feature branch.
3. Writes and commits the progress log header.

`codingAgent.cheap` derives a short label from the prompt; its slug is the
branch name. `--branch <name>` sets the name and overrides the flow's
[`branchNaming`](../authoring/extending.md). A name that is protected (`main`,
`master`, or the repository's default branch) or already exists is refused;
Orca does not pick another.

## Run targets

Three flags decide where a run works.

| Flag | Works on | Uncommitted files on a fresh run |
|---|---|---|
| none (the default) | a new branch | asked: stash (default), keep or abort; stashed when there is no terminal |
| `--skip-branch` | the current branch | kept; they are swept into the first stage's commit |
| `--keep-changes` | with or without `--skip-branch` | kept; in normal mode they reach the new branch in the first stage's commit |
| `--worktree` | a second checkout under `.orca/worktrees/<key>` | left behind; cannot be combined with `--skip-branch` or `--keep-changes` |

`--skip-branch` is for continuing work already planned on a branch. It refuses
a protected branch or a detached HEAD, and cannot be combined with `--branch`.

A re-run (any run that finds a progress log, even an unreadable one) always
stashes and ignores `--keep-changes`, so the interrupted stage's partial work
cannot leak into the stage that runs again.

In the flow the flags appear as `OrcaArgs.target`, of type `RunTarget`:
`NewBranch(uncommitted)`, `CurrentBranch(uncommitted)` or `Worktree`, where
`uncommitted` is `Uncommitted.Stash` or `Uncommitted.Keep`. It has one case
per allowed combination, so a refused combination cannot be written in code. A
script can set the field itself, and that overrides the flags:
`flow(OrcaArgs(args).copy(target = RunTarget.Worktree))`.

### Worktrees

`--worktree` runs the whole flow in `.orca/worktrees/<key>` of the repository.
The checkout is keyed on the same prompt hash as the progress log, created on
the first attempt and reused by every later attempt for that prompt. Two runs
never share a checkout or a branch.

Things to know:

- A worktree is made from a commit, so uncommitted work does not come along.
- The first attempt starts from a cold checkout: no build outputs, no
  downloaded dependencies, no untracked local config.
- An editor or indexer that ignores `.gitignore` will see the second checkout.
- Orca never removes the worktree or its `orca-worktree-<key>` branch. Full
  cleanup is `git worktree remove .orca/worktrees/<key>` and
  `git branch -d orca-worktree-<key>`.
- If the `orca-worktree-<key>` branch gained commits outside Orca since the
  worktree was created, a re-run refuses instead of moving it.

## Resume

A re-run with the same prompt finds the progress log and resumes from the first
incomplete stage. A `--branch` naming a different branch than the log's is
refused. On resume, Orca prints which branch the run is bound to, how many
stages are already done, and that the interrupted stage's uncommitted work was
dropped. Each [durable session](../authoring/talking-to-agents.md) it resumes
gets the same note.

A corrupt or truncated progress log is detected at startup. Orca warns and
starts fresh, re-running previous stages, rather than mis-resuming silently.

## Success

A final commit removes the progress log. It is pushed if the flow already
pushed the branch. If the feature branch has no real changes against the
starting branch, it is deleted and HEAD returns to the starting branch.

Otherwise the branch is kept, and where HEAD lands follows the run:

- A run that created a branch and opened a PR hands you back the branch you
  started on. The work is on the PR.
- Every other run leaves you where you were: on the feature branch when no PR
  was opened or under `--skip-branch`, and untouched under `--worktree`, where
  the work is in the separate checkout the summary names.

The [closing summary](output-and-files.md#closing-summary) names the branch you
are left on.

## Failure

While HEAD is on the feature branch, the failed stage's uncommitted partial
edits are discarded: `git reset --hard` for tracked files, plus `git clean -fd`
for the files it newly created. The run stays on the feature branch, so a
re-run resumes in place. Gitignored paths and `.orca/` are never removed.

If a fresh run kept uncommitted changes (`--skip-branch`, `--keep-changes`, or
answering keep), Orca cannot tell your untracked files from the run's own.
Then no untracked file is ever deleted, in any stage, even ones the failed
stage created. This is decided once, at setup, for the whole run. Kept edits to
tracked files that no stage has committed are restored after the reset; a
re-run stashes them before it resumes.

If the flow moved HEAD off the feature branch before failing, Orca cleans
nothing and says so.
