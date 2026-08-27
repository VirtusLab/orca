# Worktree runs — specification

Issue [#150](https://github.com/VirtusLab/orca/issues/150) asks for a way to
run a flow in a git worktree, so several runs can work on one repository at
once without sharing a checkout. A `git.createWorktree` tool inside a flow
cannot deliver that: a flow's working directory is fixed before its body runs,
so a worktree created mid-flow is a directory nothing moves into. Isolation has
to be decided before the run starts.

This specification adds a run-level `--worktree` flag. Orca creates the
worktree at startup, runs the whole flow inside it, and leaves it in place when
the run ends.

## Where the run happens

`--worktree` takes no value. The worktree's location is derived, not chosen:

    <main checkout>/.orca/worktrees/<promptHash>

`promptHash` is the same 12 hex chars that already key the run's progress log
(`.orca/progress-<hash>.json`), so one task means one worktree for the lifetime
of that task. Nothing about the path comes from the task text itself — task
text is untrusted input, and a hash is the established safe key.

The `<main checkout>` is found from git, not from the current directory:
`git rev-parse --path-format=absolute --git-common-dir` names the main
checkout's `.git` from inside any worktree, so its parent is the main checkout
wherever the flag is given. The path is therefore identical whether the run
starts from the main checkout or from a worktree, and running `--worktree`
with the same task from inside the worktree it already created resolves to
that worktree and reuses it. There is no recursion to guard against.

Worktrees live inside the repository rather than beside it because the
alternative — a sibling directory — puts a directory orca creates, and never
removes, in a place orca does not own. `.orca/worktrees/` is namespaced,
always writable, and goes away when the clone does.

They live at `.orca/`'s root, not under `.orca/cache/`, because the cache
carries a `CACHEDIR.TAG` announcing itself as disposable and skippable by
backup tools. A worktree holds committed work that may not be merged anywhere,
and — after a failure — the only copy of the progress log that resumes the
run. Neither belongs anywhere advertised as safe to delete.

The location is not configurable. A settings key for a worktree root can be
added when someone needs one; until then there is one rule to learn and
nothing to misconfigure.

## Hiding the worktree from the enclosing repository

`.orca/worktrees/` gets the self-ignoring `.gitignore` that `.orca/cache/`
already gets, written before the first worktree is created and rewritten
whenever it is missing, exactly as `OrcaDir.ensureCache` does today.

This is load-bearing, not cosmetic. Without it, `git add -A` in the main
checkout stages the worktree as an embedded git repository — a gitlink to a
commit nothing can resolve — with git's own `warning: adding embedded git
repository` as the only sign. With it, the directory is invisible: `git
status` reports nothing and `git add -A` stages nothing.

Orca's own stage commits are unaffected either way: they commit through the
`:(exclude).orca/*` pathspec, which already excludes the whole directory.

## Creating and reusing

At startup, with `--worktree` given:

- **Nothing at the derived path** — create it, detached, at the current HEAD
  of the invoking checkout. Detached, because orca names the run's feature
  branch with a model call during setup, which happens after the worktree
  already exists; the branch is then created inside the worktree by the normal
  branch binding, exactly as it is for a non-worktree run.
- **A registered worktree of this repository at that path** — reuse it. This
  is what makes a resumed run land back where its progress log is.
- **A directory that is not a registered worktree of this repository** —
  refuse, naming the path. Orca does not delete or take over a directory it
  did not create.

Everything downstream follows the resolved directory: git, the filesystem
tool, every agent subprocess, the stack commands, the progress log, the run
lock, and the session manifests. All of them are already given the run's
working directory explicitly, so there is one value to change and no process
working directory to move.

Uncommitted work in the invoking checkout does **not** come along. A worktree
is created from a commit. That is the point of the isolation, and it is the
first thing the documentation has to say.

## What the flag refuses

Two combinations are errors, refused before anything is created:

- **`--worktree --skip-branch`.** `--skip-branch` binds the run to the branch
  that is checked out now; git will not check that branch out a second time in
  a new worktree. There is no useful reading of the pair.
- **`--worktree --keep-changes`.** `--keep-changes` asks orca to leave
  uncommitted files in place for the flow to work on. Those files are in the
  other checkout and are not carried over, so honouring the flag would be a
  lie. A fresh worktree is clean by construction, which also means the
  dirty-tree prompt never fires for a worktree run.

## Resuming

Resume is unchanged in mechanism and unchanged in what the user does: run the
same flow with the same task text, and add `--worktree` again. The task
resolves to the same worktree, the worktree holds the progress log on the
run's branch, and stage replay proceeds as it does for any other run.

Two shell features currently look only at the directory they were started in,
and would silently stop working for worktree runs:

- **"Resume interrupted run"** scans for an unfinished progress log. It must
  scan every worktree of the repository, and relaunch a run found in one with
  `--worktree` set, so the relaunch returns to it.
- **`orca continue`** lists recorded sessions from `.orca/cache/runs/`. Each
  worktree has its own, so the listing must span every worktree of the
  repository. Resuming a session already uses the working directory recorded
  in its manifest, so once a session is *found*, reattaching to it works
  without further change.

Both use git's own worktree list as the source of truth. Neither can rely on
the nesting: the existing progress-log scan lists `.orca/` one level deep and
filters to files, so it does not — and must not start to — descend into
`worktrees/`.

## Telling the user where the run left them

A run that ends leaves the user's shell where it started — in the invoking
checkout, not in the worktree. Today's closing summary says "done — you are on
branch X" and offers `git diff <base>`; for a worktree run both are wrong. It
must name the worktree path and the branch inside it, and offer a command the
user can actually run from where they are standing.

The shell's flow-finished notice needs the same treatment. The main menu's
`branch:` line does not: it reads the checkout the shell is running in, whose
branch a worktree run never changes, so it stays true. What it cannot say is
that work landed elsewhere — the flow-finished notice is where that belongs.

## Cost of living inside the repository

A worktree is a full second checkout of the project, including a second copy
of its build definition, sitting inside the project directory. Tools that
honour `.gitignore` — ripgrep, fd, most language servers — skip it. Tools that
do not — some IDE indexers, `find`, broad glob patterns, file watchers — will
see the duplicate. There is no clean mitigation: a `CACHEDIR.TAG` would keep
some of them out, but it also tells backup tools to skip the directory, which
is exactly wrong for one holding unmerged work.

Repository size grows by a checkout per concurrent worktree, and does so
invisibly from outside the project directory.

## Cold build trees

A fresh worktree has no build outputs, no installed dependencies, and none of
the untracked local configuration a project may need. The stack commands
(format, lint, test) run there, so the first worktree run pays a cold build,
and a project that cannot build without untracked local files will fail in a
worktree and succeed outside one.

This is documented, not solved. A post-creation setup hook can be added later
if it turns out to be needed.

## Lifetime

Orca never removes a worktree. A failed run's worktree holds the progress log
that resumes it, and a successful run's worktree holds the branch with the
work on it — in both cases removing it would destroy something the user still
wants. Cleanup is `git worktree remove`, which works normally on the nested
path, and the closing summary is where the user learns the path to hand it.

Nothing else deletes it by accident. `git clean -xdf` in the main checkout
skips it, reporting `Skipping repository .orca/worktrees/<hash>` — git treats
it as a repository boundary. Only `git clean -xdff` removes it, and that
leaves the worktree's administrative entry behind as `prunable`. A plain
`git clean -xdf` does delete the `.gitignore` under `.orca/worktrees/`, which
un-hides the directory until the next run rewrites the marker.

## Concurrency

Each worktree has its own `.orca/cache/`, and the run lock is keyed on the run
directory, so two runs in two worktrees of one repository do not contend —
which is the isolation the issue asks for. The one shared resource is the
branch namespace: a run cannot create a branch checked out in another
worktree, and git says so plainly (`fatal: '<branch>' is already used by
worktree at ...`), which orca surfaces as-is.
