# Git, GitHub and file tools

Three tools are available inside a `flow(...)` body: `git`, `gh` and `fs`.
Reads work anywhere in a flow. Writes compile only inside a `stage(...)` body,
see [Stages](../authoring/stages.md).

The runtime owns git. Agents edit the working tree; they are told not to
commit, push or switch branches. The runtime commits each stage and owns the
run's branch. The flow pushes with `git.push`. So `git` has no commit or
checkout methods. `git` also cannot be swapped out in `flow(...)`; `gh`, `fs`
and the agents can, see
[Extending flows in code](../authoring/extending.md).

## `git`

Reads against the working tree, plus `push`. Commits and branch names are
typed: `orca.gitref.CommitHash`, `orca.gitref.BranchName`, and
`orca.gitref.Head`, which is a branch or a detached commit.

| Method | Returns |
|---|---|
| `push()` | `Either[PushFailure, Unit]`. `PushFailure` is `NonFastForward` or `RemoteDeclined` |
| `head()` | the branch HEAD is on, or the commit it is detached at |
| `headCommit()` | the commit HEAD points at, if any |
| `isAncestorOfHead(commit)`, `branchExists(name)`, `isIgnored(path)` | booleans; `isIgnored` answers `false` when git cannot answer |
| `uncommittedDiff()` | the whole repository minus `.orca/` bookkeeping, tracked files only; empty once the work is committed |
| `defaultBase()` | the default base branch, or `Left(NoDefaultBase)` |
| `diffVsBase(base)` | the branch-wide diff against `base` |
| `changedFiles(since?)` | the changed paths. Use it, not the diff text, to decide by file name: the diff does not show binary changes or renames, and leaves a trailing tab on a path containing a space |
| `reviewChanges(since?)` | what reviewers get: the diff, the full contents of new files, every changed path with its change size, and each file's own diff. `since` is a commit to compare against, so committed work is included |
| `pendingChanges()` | what the next commit will include: a `--stat` summary, the new files and the diff |
| `show(rev, paths?)` | `git show` of a revision, optionally limited to paths; long output is cut and says so |
| `fileAt(rev, path)` | one file's contents at `rev` |

`NoDefaultBase`, `PushFailure` and `GitReadFailed` come back as `Left`. Call
`.orThrow` where you do not expect the `Left`; it throws instead.

## `gh`

GitHub PR and CI integration through the `gh` CLI. `pr` is a
[`PrHandle`](data-structures.md#labels-and-handles), `issue` an `IssueHandle`.

| Method | Does |
|---|---|
| `availability()` | read-only probe of whether a PR can be opened from this checkout; answers with a [`GitHubAvailability`](data-structures.md#labels-and-handles) |
| `createPr(title, body)` | opens a PR, `Either[PrCreateFailed, PrHandle]`; idempotent by branch: returns the existing PR if one is open |
| `updatePr(pr, title, body)` | replaces a PR's title and body; harmless to re-run |
| `readIssue(issue)`, `readIssueComments(issue)`, `readPrComments(pr)` | read an issue, its comments, or a PR's comments |
| `writeComment(pr, body)` / `writeComment(issue, body)` | posts a comment |
| `upsertComment(pr, marker, body)` / `upsertComment(issue, marker, body)` | finds a prior comment carrying `marker` and edits it in place, else posts one |
| `buildStatus(pr)`, `waitForBuild(pr, ...)` | CI status; `waitForBuild` returns `Either[BuildWaitFailed, BuildStatus]` |

`createPr` and `upsertComment` are idempotent, which is what makes a resumed
stage safe, see [Pull requests](../authoring/pull-requests.md).

## `fs`

Working-tree file I/O.

| Method | Does |
|---|---|
| `read(path)` | `Option[String]`; `None` for a missing file, no exception |
| `write(path, content)` | refuses a path outside the working tree or under `.orca/runs`, `.orca/cache` or `.orca/worktrees` |
| `list(glob)` | the paths matching a glob, as `List[String]` |
