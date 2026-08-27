# Worktree runs — implementation plan

Expands [specification.md](specification.md). All paths repo-relative. Planned
against `master` @ `c0dad27a`; every file/line reference was re-read there, and
every git behaviour asserted below was verified against git 2.53 in a scratch
repository (see "Verified git behaviour").

## Landing order

**PR 1 first; PR 2 and PR 3 then in either order.** PR 1 adds the flag and the
resolution, and nothing works without it. PR 2 (closing summary) and PR 3
(shell) touch disjoint files.

PR 2 and PR 3 each fix a way the feature is *incomplete* rather than broken —
after PR 1 a worktree run works, but does not say where it went (PR 2) and is
only reachable by typing the flag on a flow script (PR 3). Neither is
optional; both are separable.

## ADRs

No new ADR. Three amendments, each because the existing text states something
this plan makes untrue:

- **ADR 0019 §"`.orca/` flips to committed-by-default"** (`:102–141`) — the
  directory listing at `:111` gains `worktrees/`, and the paragraph at `:118`
  ("ephemeral state moves under `.orca/cache/`") needs the exception stated:
  worktrees are ignored but not ephemeral, and deliberately sit outside
  `cache/`. Same amendment names `OrcaDir` as the one helper that writes the
  new directory's marker, consistent with `:125`.
- **ADR 0018 §2.5** (`:344`) — documentation only; no code under it changes.
  One dated amendment recording that a worktree run always meets the
  dirty-tree policy's clean case, and that `--worktree` is refused with
  `--skip-branch` and with `--keep-changes` before setup ever runs.
- **ADR 0021 §10** (`:834`) — the `orca run` flag table gains `--worktree`;
  §3 (`:160`) and §8 (`:399`) gain the "scan every worktree" rule for the
  resume offer and the session listing.

---

# PR 1 — `--worktree`: create or reuse the run's worktree

## Behavior decisions

**The git plumbing goes in `tools`, not on `GitTool`.** `GitTool` is the
flow-facing tool trait; worktree operations were deleted from it in the
2026-08 review precisely because no flow can use them, and nothing here
changes that — resolution runs before any tool exists. New object
`orca.tools.Worktrees` (`tools/src/main/scala/orca/tools/Worktrees.scala`),
`private[orca]`, shelling out through `orca.subprocess.QuietProc` the way
`ConfigSummary` already does for `git rev-parse`. Two reads and one write:

- `mainCheckout(cwd): Option[os.Path]` — `git rev-parse
  --path-format=absolute --git-common-dir`, then its parent. The
  `--path-format=absolute` is required: the bare form returns a path relative
  to the main checkout, which is useless from inside a linked worktree. `None`
  outside a git repository.
- `list(cwd): List[os.Path]` — the paths from `git worktree list --porcelain`,
  empty outside a git repository. Paths are all any caller needs: PR 1
  distinguishes reuse from refusal by testing the path on disk, and PR 3's
  scans read each path's `.orca/`. No branch or `prunable` field — nothing
  consumes one.
- `add(cwd, path)` — `git worktree add --detach <path>`, with no commit-ish:
  git then detaches at `cwd`'s own HEAD, which is exactly the specified start
  point, so there is nothing to resolve first. No `--` separator: the path is
  absolute and its last segment is a 12-hex hash, so it cannot be read as a
  flag.

**Run-level policy stays in `runner`.** New object `orca.runner.WorktreeRun`
(`runner/src/main/scala/orca/runner/WorktreeRun.scala`): derive the path,
create-or-reuse, and produce the resolved directory. It owns the only
knowledge of how the path is built, so PR 3's relaunch does not re-derive it.

**The path.** `OrcaDir.worktreesPath(mainCheckout) / hash`, where `hash` is
`ProgressStore.hashPrompt(args.userPrompt)` — the identical call
`ProgressStore.default` makes at `flow/.../ProgressStore.scala:71`, so the
worktree and the progress log inside it always carry the same hash.
`hashPrompt` is `private[orca]` and `runner` is `package orca`, so no
visibility change is needed.

**`OrcaDir` gains the directory and its marker.**
`tools/src/main/scala/orca/OrcaDir.scala`:

- `worktreesPath(workDir)` — passive, `root(workDir) / "worktrees"`,
  alongside `runsPath` (`:111`).
- `ensureWorktrees(workDir)` — `abortIfOrcaComponentSymlink`, `makeDir.all`,
  then `writeIfAbsent(dir / ".gitignore", gitignoreContents)`, mirroring
  `ensureCache` (`:72–79`) minus the `CACHEDIR.TAG`. The tag is deliberately
  absent: it tells backup tools to skip the directory, and a worktree holds
  unmerged work. Say that in the scaladoc, or someone will add it for
  symmetry.

The marker is not optional. Without it `git add -A` in the main checkout
stages the worktree as an embedded repository — verified, see below — so
`ensureWorktrees` must run before `Worktrees.add`, not after.

**Resolution happens in `flow()`, not `runFlow`.** This is forced by
`RunManifestWriter.start` at `runner/.../flow.scala:172`, which is in `flow()`
and takes `workDir`: resolving inside `runFlow` would write the run's session
manifests into the *invoking* checkout while the run itself happened in the
worktree, and PR 3's `orca continue` would then find them under the wrong
directory. `runFlow` keeps no worktree logic and its `workDir` parameter is
already-resolved for every caller, tests included.

Concretely, in `flow()` (`runner/.../flow.scala:144–178`):

- Resolve after `installUncaughtExceptionHandler()` (`:154`) and before
  `RunManifestWriter.start` (`:172`).
- `flowLog.info` at `:150` logs the *resolved* directory, and says when it is
  a worktree; move it below the resolution.
- A resolution failure has no dispatcher and no manifest, so it must reach
  `flow()`'s stderr backstop and exit 1 — the same treatment the `NonFatal`
  catch at `:214–216` gives a pre-dispatcher failure. `RunManifestWriter.start`
  is *not* inside that try today, so simply throwing before it escapes
  uncaught. Shape: have `WorktreeRun.resolve` return
  `Either[String, os.Path]`, and on `Left` print `[orca] <message>`, set
  `outcome = FlowOutcome.Failed`, and skip the `supervised:` block —
  which requires hoisting `orcaLog.finish()` out of the inner `finally`
  (`:223`) so it still runs on that path. The trailing
  `if outcome == FlowOutcome.Failed then System.exit(1)` (`:229`) then needs
  no change.
- `flow()`'s scaladoc documents `workDir` as "the default is the current
  directory"; it gains the `--worktree` exception.
- Pass the resolved directory as `runFlow`'s `workDir`. Inside `runFlow`
  nothing changes: every downstream consumer already takes `workDir`
  explicitly (`:274` lock, `:297` store, `:304` agent wiring, `:311/:313/:315`
  git/gh/fs, `:318` context).

**The flag.** `runner/src/main/scala/orca/OrcaArgs.scala` gains
`worktree: Flag = Flag()` with doc "run the flow in a git worktree of this
repository instead of the current checkout".

**The refusals live in `OrcaArgs.parse`** (`:23–26`), not in `WorktreeRun`.
`parse` already returns `Either[String, OrcaArgs]` and `from` already turns a
`Left` into `OrcaFlowException`, so a contradictory flag pair fails at
`OrcaArgs(args)` — before `flow()` runs, before the banner, before anything
touches git. Two messages, each naming both flags and why they conflict:
`--worktree` with `--skip-branch` (git will not check the current branch out
twice), `--worktree` with `--keep-changes` (uncommitted files stay in the
invoking checkout).

**Not a repository, or no commits yet.** Resolution runs before
`FlowLifecycle.abortIfNoCommits` (`:386`, called at `:331`), so with
`--worktree` those two cases now surface here instead. `resolve` returns a
`Left` for each — `mainCheckout` returning `None` for the first, `add` failing
for the second — reusing `abortIfNoCommits`' wording for the unborn-HEAD case
rather than passing git's raw error through.

**Reuse and refusal.** `WorktreeRun.resolve` on an existing derived path:
registered worktree of this repository → return it; anything else (a plain
directory, a worktree of a different repository, a file) → `Left` naming the
path and saying orca will not take over a directory it did not create. The
`prunable` case — the directory is gone but the admin entry survives, which
`git clean -xdff` produces — is a `list` entry whose path does not exist:
treat as absent and let `Worktrees.add` run, which prunes and recreates.

## Verified git behaviour

Checked against git 2.53 with a nested worktree at
`main/.orca/worktrees/<hash>`; these are the facts the code above depends on.

| Behaviour | Result |
|---|---|
| `git worktree add --detach .orca/worktrees/<hash>` inside the tree | allowed, no warning |
| `git worktree add --detach <path>` with no commit-ish | detaches at the invoking checkout's HEAD |
| `git status` in main, no ignore file | `?? .orca/worktrees/` |
| `git add -A` in main, no ignore file | stages it as a gitlink, `warning: adding embedded git repository` |
| with `.orca/worktrees/.gitignore` = `*` | `git status` empty, `git add -A` stages nothing |
| `git add -A -- . ':(exclude).orca/*'` (orca's own commits) | worktree never staged, ignore file or not |
| `git clean -xdf` in main | `Skipping repository .orca/worktrees/<hash>`; removes only the `.gitignore` |
| `git clean -xdff` in main | removes it; admin entry survives, listed `prunable` |
| `git worktree remove .orca/worktrees/<hash>` | removes tree and admin entry |
| `git rev-parse --path-format=absolute --git-common-dir` | `<main>/.git` from main *and* from the worktree |
| `.orca/` inside the worktree | committed content only; `worktrees/` absent |
| checking out the worktree's branch in main | `fatal: '<branch>' is already used by worktree at ...` |

## Tests

- `tools/src/test/scala/orca/tools/WorktreesTest.scala` (new) — `add` then
  `list` round-trip in a temp repo; `mainCheckout` from both the main checkout
  and the linked worktree returning the same path; a `--`-requiring path
  (leading `-`) not being read as a flag.
- `runner/src/test/scala/orca/runner/WorktreeRunTest.scala` (new) — derives
  the same path for the same task text and a different one for different text;
  reuses an existing registered worktree; refuses a plain directory at the
  path; treats a prunable entry as absent; writes the `.gitignore` before
  creating.
- `runner/src/test/scala/orca/OrcaArgsTest.scala` — the two refusals, and
  `--worktree` alone parsing.
- `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala` — one end-to-end
  run with `--worktree`: work lands on a branch in the worktree, the main
  checkout's branch and tree are untouched, the progress log is inside the
  worktree, and a second run with the same task text resumes in the same
  worktree. It must drive `flow(...)`, not `runFlow(...)` — resolution lives in
  `flow()`, so a `runFlow` test would pass an already-resolved directory and
  exercise none of it. The file already has both styles (`flow(` at `:76`).

## Docs

`README.md:63` (the useful-flags sentence) and `README.md:954` (the `orca run`
flag row) gain `--worktree`. The prose block at `README.md:327–338`, which
explains `--skip-branch`/`--keep-changes` against the progress header, gains a
paragraph: what a worktree run isolates, that uncommitted work does not come
with it, that the first run in a worktree pays a cold build, that an editor or
indexer not honouring `.gitignore` will see the second checkout, and that
cleanup is `git worktree remove`.

---

# PR 2 — Say where the run left the user

## Behavior decisions

**`ClosingSummary` learns about the worktree.**
`runner/src/main/scala/orca/runner/ClosingSummary.scala:34`, `lines(branch,
changes)`, gains one parameter: `worktree: Option[os.Path]`, `Some` only for a
worktree run. `None` reproduces today's output byte-for-byte — every existing
case keeps passing unchanged.

For a worktree run the first line names both the path and the branch inside
it, per the specification, and the `next:` line becomes `git -C <path> diff
<target>` so it works from where the user actually is. `<target>` keeps its
existing two shapes (`<base>`, or `<base>..<countedOn>` when HEAD left the
counted branch) — the `-C` is the only change to it.

**Caller.** `FlowLifecycle.teardownSuccess` (`:1147`) emits the summary at
`:1191`. It has `setup: FlowSetup` and `git`, but not the run directory —
`FlowSetup` (built in `setup`, `:297–376`) does not carry `workDir` today. Add
a single `worktree: Option[os.Path]` field there rather than a directory plus a
Boolean, and rather than a second `teardownSuccess` parameter: `setup` already
receives both `args` (`:298`) and `workDir` (`:303`), so the field is
`Option.when(args.worktree.value)(workDir)` computed at one site.

**Failure path.** A failed run prints no closing summary today and should not
start; the worktree path reaches the user through the abort message only if the
failure is the resolution itself, which PR 1 already words. No change.

## Tests

`runner/src/test/scala/orca/runner/` — a `ClosingSummary` case per shape:
non-worktree unchanged, worktree with changes, worktree with none. The
end-to-end assertion belongs with PR 1's `FlowLifecycleTest` case; extend it
here to assert the emitted `Step` names the worktree.

---

# PR 3 — Shell: the flag, the prompt, and worktree-wide scans

## Behavior decisions

**`FlowFlags` gains `worktree`.**
`shell/src/main/scala/orca/shell/run/FlowLauncher.scala:30` — the bundle
exists precisely so a new flag is one field rather than another positional
Boolean. Threaded through `argv` (`:97`): a `worktreeArgs` alongside
`verboseArgs`/`skipBranchArgs`/`keepChangesArgs` (`:108–111`), appended after
`--` at `:117` with the others. Four construction sites update:
`Cli.scala:165`, `Main.scala:365`, `Main.scala:407`, and
`AuthorAction.scala:140` (which passes all-false and stays all-false —
authoring runs in a sandbox, not the user's repo).

**`orca run --worktree`.** `Cli.scala`'s `run` (`:144`) gains the flag next to
`honorPin` (`:156–160`), passed into `FlowFlags`.

**The refusals get a shell fast path.** `RunCli.run` already returns
`ExitCodes.UsageError` for bad input. Rejecting `--worktree --skip-branch` and
`--worktree --keep-changes` there saves a `scala-cli` spawn and a dependency
resolution before the child says the same thing. The child's own refusal (PR 1)
stays as the authority; this only makes it fast. Same wording in both places.

**Interactive prompt.** `Main.runFlow` (`:356`) asks for the flow, the task,
then whether to create a branch (`promptCreateBranch`, `:435`). A worktree
question goes after it, and only when the branch answer was yes — "run in a
worktree" and "run on the current branch" are the incompatible pair, so asking
both independently invites the user to pick a combination orca refuses.

**The two scans take the directories, they do not discover them.** Both
`ResumeDetector.detect` and `ManifestReader.list` are today driven by tests
that seed a bare temp directory — no git repository in sight. If either starts
calling `git worktree list` internally, those tests break for a reason that has
nothing to do with what they assert. So each takes the directories to scan, and
one small helper (`Worktrees.list(os.pwd)`, falling back to just `os.pwd` when
it comes back empty) resolves them at the two `Main`/`Cli` call sites. This is
the seam convention `Cli`'s own scaladoc already states: explicit `workDir`
params rather than reading `os.pwd` inside.

**Resume relaunches into the worktree.**
`shell/src/main/scala/orca/shell/resume/ResumeDetector.scala` currently reads
one directory (`detect(workDir)`, `:38`); it takes the list instead, keeping
its newest-by-mtime rule across all of them. `InterruptedRun` (`:8`) gains a
flag saying the log was found in a worktree. `Main.resumeInterruptedRun`
(`:386`) then passes `worktree = true` in the `FlowFlags` it builds at `:407`,
and the derived path takes the relaunch back to the same worktree. The
existing comment at `:378–384` explaining why the other flags are all-false
needs the new flag's *different* reason recorded: it is not inert, it is what
makes the resume land in the right tree.

**Session listing spans worktrees.** `ManifestReader.list(workDir, pidAlive)`
(`sessions/ManifestReader.scala:30`) reads one `.orca/cache/runs/`; it takes
the list instead, keeping its per-file warning behaviour and its newest-first
sort, which now orders across directories. Its two callers —
`ContinueCli.runContinue` (whose `workDir` is already an explicit test seam)
and `Main.loop` (`:115`) — resolve the list at their call site.
`SessionAction.resume` already uses each manifest's recorded `workDir`
(`:100`), so nothing about reattaching changes.

**Do not scan by nesting.** `ProgressScan.progressLogPaths`
(`flow/.../ProgressScan.scala:26`) lists `.orca/` one level deep and filters to
files, so it does not descend into `worktrees/` — which is correct and must
stay that way. Discovery goes through `git worktree list`, so it also finds
worktrees outside the repository (someone's pre-existing `../feature-x`), not
only the ones orca made.

**Not changed: the menu's `branch:` line.** `ConfigSummary.branchLine`
(`actions/ConfigSummary.scala:58`) reads the shell's own checkout, whose branch
a worktree run never touches, so it stays true.

## Tests

- `shell/src/test/scala/orca/shell/run/FlowLauncherTest.scala` — `--worktree`
  lands after `--`, with the other flow flags.
- `shell/src/test/scala/orca/shell/cli/CliTest.scala` — the flag parses; the
  two fast-path refusals return `UsageError`.
- `shell/src/test/scala/orca/shell/resume/ResumeDetectorTest.scala` — a log in
  a linked worktree is detected and reported as a worktree run; the existing
  main-checkout cases keep passing.
- `shell/src/test/scala/orca/shell/sessions/ManifestReaderTest.scala` —
  manifests from two worktrees appear in one listing, newest-first across both.
- `shell/src/test/scala/orca/shell/actions/RunActionTest.scala` — the
  interactive path's flag reaches the launcher.

## Docs

ADR 0021 §10's flag table, §3's resume-offer description, and §8's session
listing, per the ADRs section above. `README.md:954` is PR 1's edit; no second
README change is needed here beyond the interactive-shell walkthrough if it
enumerates the run prompts.
