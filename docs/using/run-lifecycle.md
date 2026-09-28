# Branches, resume and worktrees

A **run** is the execution of a flow for one prompt, across however many
processes it takes to finish. An **attempt** is one of those processes: a
single `orca run`, or a single `flow(...)` call. When a run is interrupted, you
resume it by attempting it again with the same prompt.

Each run is bound to exactly one feature branch and one progress log,
`.orca/runs/<key>.progress.json`, where `<key>` is derived from the prompt. The
log records which [stages](../glossary/users.md#flows-and-runs) have finished,
and with what results.

## Start

On a fresh run, Orca:

1. asks what to do with uncommitted changes; stashing is the default (see
   [Run targets](#run-targets)), and stashed changes come back with
   `git stash pop`,
2. creates and checks out the feature branch,
3. writes and commits the progress log header.

The branch name comes from `codingAgent.cheap`, which derives a short label
from the prompt; the label's slug is the branch name. You can set the name
yourself with `--branch <name>`, which overrides the flow's
[`branchNaming`](../authoring/extending.md). A name that is protected (`main`,
`master`, or the repository's default branch), or that already exists, is
refused; Orca does not pick another one.

## Run targets

Three flags decide where a run works, and what happens to uncommitted files on
a fresh run:

- With no flag (the default), the run works on a new branch. You are asked
  what to do with uncommitted files: stash (the default), keep or abort. When
  there is no terminal, they are stashed.
- `--skip-branch` works on the current branch. Uncommitted files are kept and
  swept into the first stage's commit.
- `--keep-changes` can be used with or without `--skip-branch`. Uncommitted
  files are kept; in normal mode they reach the new branch in the first
  stage's commit.
- `--worktree` works in a second checkout under `.orca/worktrees/<key>`.
  Uncommitted files are left behind. It cannot be combined with
  `--skip-branch` or `--keep-changes`.

`--skip-branch` is meant for continuing work that was already planned on a
branch. It refuses a protected branch or a detached HEAD, and cannot be
combined with `--branch`.

```{note}
A re-run, that is any run that finds a progress log (even an unreadable one),
always stashes and ignores `--keep-changes`. This way the interrupted stage's
partial work cannot leak into the stage that runs again.
```

In the flow, the flags appear as `OrcaArgs.target`, of type `RunTarget`. Its
cases are `NewBranch(uncommitted)`, `CurrentBranch(uncommitted)` and
`Worktree`, where `uncommitted` is either `Uncommitted.Stash` or
`Uncommitted.Keep`. There is one case per allowed combination, so a refused
combination cannot be written in code. A script can also set the field itself,
which overrides the flags:

```scala
flow(OrcaArgs(args).copy(target = RunTarget.Worktree))
```

### Worktrees

With `--worktree`, the whole flow runs in `.orca/worktrees/<key>` inside the
repository. The checkout is keyed on the same prompt hash as the progress log:
it is created on the first attempt and reused by every later attempt for that
prompt. Two runs never share a checkout or a branch.

A few things are worth knowing:

- A worktree is made from a commit, so uncommitted work does not come along.
- The first attempt starts from a cold checkout: no build outputs, no
  downloaded dependencies, no untracked local config.
- An editor or indexer that ignores `.gitignore` will see the second checkout.
- Orca never removes the worktree or its `orca-worktree-<key>` branch. Full
  cleanup is `git worktree remove .orca/worktrees/<key>` followed by
  `git branch -d orca-worktree-<key>`.
- If the `orca-worktree-<key>` branch gained commits outside Orca since the
  worktree was created, a re-run refuses instead of moving it.

## Resume

A re-run with the same prompt finds the progress log and resumes from the first
incomplete stage. If `--branch` names a different branch than the one in the
log, the run is refused. On resume, Orca prints which branch the run is bound
to, how many stages are already done, and that the interrupted stage's
uncommitted work was dropped. Each
[durable session](../authoring/talking-to-agents.md) that is resumed gets the
same note.

A corrupt or truncated progress log is detected at startup. In that case Orca
warns and starts fresh, re-running the previous stages, rather than resuming
from the wrong place silently.

## Success

When the flow succeeds, a final commit removes the progress log. It is pushed
if the flow already pushed the branch. If the feature branch has no real
changes against the starting branch, the branch is deleted and HEAD returns to
the starting branch.

Otherwise the branch is kept, and where HEAD lands depends on the run:

- A run that created a branch and opened a PR hands you back the branch you
  started on; the work is on the PR.
- Every other run leaves you where you were: on the feature branch when no PR
  was opened or under `--skip-branch`, and untouched under `--worktree`, where
  the work is in the separate checkout that the summary names.

In every case the [closing summary](output-and-files.md#closing-summary) names
the branch you are left on.

## Failure

While HEAD is on the feature branch, the failed stage's uncommitted partial
edits are discarded: `git reset --hard` for tracked files, plus `git clean -fd`
for the files the stage newly created. The run stays on the feature branch, so
a re-run resumes in place. Gitignored paths and `.orca/` are never removed.

If a fresh run kept uncommitted changes (through `--skip-branch`,
`--keep-changes`, or by answering keep), Orca cannot tell your untracked files
from the run's own. In that case no untracked file is ever deleted, in any
stage, not even the ones the failed stage created. This is decided once, at
setup, for the whole run. Kept edits to tracked files that no stage has
committed are restored after the reset, and a re-run stashes them before it
resumes.

If the flow moved HEAD off the feature branch before failing, Orca cleans
nothing and says so.
