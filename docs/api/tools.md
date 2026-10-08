# Git, GitHub and file tools

Three tools are available inside a `flow(...)` body: `git`, `gh` and `fs`. You
can read through them anywhere in a flow, but writes compile only inside a
`stage(...)` body; see [Stages](../authoring/stages.md).

The runtime owns git. Agents edit the working tree, and they are told not to
commit, push or switch branches. The runtime commits each stage and owns the
run's branch, and the flow pushes with `git.push`. This is why `git` has no
commit or checkout methods.

Note that `git` cannot be swapped out in `flow(...)`, while `gh`, `fs` and the
agents can; see [Extending flows in code](../authoring/extending.md).

## `git`

`git` offers reads against the working tree, plus `push`. Commits and branch
names are typed: `orca.gitref.CommitHash`, `orca.gitref.BranchName`, and
`orca.gitref.Head`, which is a branch or a detached commit.

| Method | Returns |
|---|---|
| `push()` | `Either[PushFailure, Unit]` |
| `head()` | the branch HEAD is on, or the commit it is detached at |
| `headCommit()` | the commit HEAD points at, if any |
| `isAncestorOfHead(commit)`, `branchExists(name)`, `isIgnored(path)` | booleans |
| `uncommittedDiff()` | the diff of the whole repository, tracked files only |
| `defaultBase()` | the default base branch, or `Left(NoDefaultBase)` |
| `diffVsBase(base)` | the branch-wide diff against `base` |
| `changedFiles(since?)` | the changed paths |
| `reviewChanges(since?)` | what reviewers get: the diff, new files, changed paths and per-file diffs |
| `pendingChanges()` | what the next commit will include |
| `show(rev, paths?)` | `git show` of a revision, optionally limited to paths |
| `fileAt(rev, path)` | one file's contents at `rev` |

A few of these deserve more detail:

- `PushFailure` is either `NonFastForward` or `RemoteDeclined`.
- `isIgnored` answers `false` when git cannot answer.
- `uncommittedDiff()` leaves out the `.orca/` bookkeeping, and is empty once
  the work is committed.
- `changedFiles(since?)` is what to use when you decide by file name; do not
  parse the diff text for that. The diff does not show binary changes or
  renames, and leaves a trailing tab on a path containing a space.
- `reviewChanges(since?)` returns the diff, the full contents of new files,
  every changed path with its change size, and each file's own diff. `since`
  is a commit to compare against, so committed work is included.
- `pendingChanges()` returns a `--stat` summary, the new files and the diff.
- `show` cuts long output and says so.

`NoDefaultBase`, `PushFailure` and `GitReadFailed` come back as `Left`. Where
you do not expect a `Left`, call `.orThrow`, which throws instead of returning
it.

## `gh`

`gh` is the GitHub PR and CI integration, going through the `gh` CLI. In the
methods below, `pr` is a [`PrHandle`](data-structures.md#labels-and-handles)
and `issue` an `IssueHandle`.

| Method | Does |
|---|---|
| `availability()` | probes whether a PR can be opened from this checkout |
| `prHandle(ref)` | a `PrHandle` from a PR URL or `owner/repo#N`; `Either[String, PrHandle]` |
| `createPr(title, body)` | opens a PR; `Either[PrCreateFailed, PrHandle]` |
| `updatePr(pr, title, body)` | replaces a PR's title and body |
| `readIssue(issue)`, `readIssueComments(issue)`, `readPrComments(pr)` | read an issue, its comments, or a PR's comments |
| `writeComment(pr, body)`, `writeComment(issue, body)` | posts a comment |
| `upsertComment(pr, marker, body)`, `upsertComment(issue, marker, body)` | edits the prior comment carrying `marker`, else posts one |
| `buildStatus(pr)`, `waitForBuild(pr, ...)` | CI status |

`availability()` is read-only and answers with a
[`GitHubAvailability`](data-structures.md#labels-and-handles). `waitForBuild`
returns `Either[BuildWaitFailed, BuildStatus]`.

`createPr` is idempotent by branch: if a PR is already open for the branch, it
returns that one. `upsertComment` finds a prior comment carrying `marker` and
edits it in place, posting a new one only when there is none. `updatePr` is
harmless to re-run. This idempotency is what makes a resumed stage safe, see
[Pull requests](../authoring/pull-requests.md).

## `fs`

`fs` is working-tree file I/O.

| Method | Does |
|---|---|
| `read(path)` | `Option[String]`; `None` for a missing file |
| `write(path, content)` | writes a file; refuses paths outside the working tree |
| `list(glob)` | the paths matching a glob, as `List[String]` |

`read` does not throw for a missing file; you get `None`. `write` refuses a
path outside the working tree, and also one under `.orca/runs`, `.orca/cache`
or `.orca/worktrees`.
