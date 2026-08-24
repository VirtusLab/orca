# The diff family materializes unbounded output; every consumer caps only after materialization

**Aspect**: correctness  **Severity**: high

## Problem

Commit 26e9100b routed the agent-facing reads (`show`, `fileAt`) through
`QuietProc.callCapped` (`gitRead`, `tools/src/main/scala/orca/tools/GitTool.scala:974-985`).
The flow-facing diff family was not touched and still fully materializes
whatever git prints:

- `trackedDiff` (GitTool.scala:729-731) → `git(...)` → `QuietProc.call`,
  which buffers all of stdout into a `String`
  (`tools/src/main/scala/orca/subprocess/QuietProc.scala:29-44`).
- `withNewFileContents` (GitTool.scala:787-791) concatenates the full tracked
  diff plus one complete, uncapped `git diff --no-index` render per untracked
  file (`untrackedFileDiff`, GitTool.scala:835-843), with `untrackedPaths()`
  using `--untracked-files=all` (GitTool.scala:797-804) so untracked
  directories are fully recursed — one subprocess spawn and one full-content
  String per file.
- Affected operations: `diff`, `reviewDiff`, `reviewChanges`,
  `pendingChanges`, `diffVsBase`, `diffBranchExcludingOrca`.

The only caps sit downstream, after the whole string exists on the heap:
`flow/src/main/scala/orca/Flow.scala:161` (`BoundedDiff.commitPayload` → 8 KB)
and `flow/src/main/scala/orca/review/ReviewDiffSource.scala:38-40`
(`reviewPayload` → 128 KB). Note the contrast: `fileAt` refuses a single
2 MB file, while `reviewDiff` will render a 2 GB untracked file whole.

This path runs on every stage commit (`pendingChanges` via
`defaultCommitMessage`) and every review round (`reviewChanges`). Concrete
failure: an agent scaffolds a project and runs `npm install` (or produces
`target/`) before any `.gitignore` covers it; the stage ends;
`untrackedPaths` lists tens of thousands of files; each is rendered as a full
new-file diff and concatenated — hundreds of MB of heap and minutes of
subprocess churn, of which `BoundedDiff` then keeps 8 KB. Worst case is an
OOM at commit time.

A second defect in the same function: `untrackedFileDiff` throws
(`fail(...)`, GitTool.scala:843) when `--no-index` cannot read the path.
`undiffableReason` (GitTool.scala:857-861) pre-screens symlink-dirs and
nested repos, but a file deleted between the `untrackedPaths()` sample and
the per-file diff (e.g. a background build removing its own temp file) turns
a review sample into a flow abort, when the established shape for
unrenderable paths is a `# skipped <path>: <reason>` line that carries on.

## Proposed solution

In `tools/src/main/scala/orca/tools/GitTool.scala` (`OsGitTool` only; trait
contracts gain one sentence about truncation):

1. Route `trackedDiff` and `untrackedFileDiff` through
   `QuietProc.callCapped` with `OsGitTool.MaxReadBytes` (2 MB — already a
   small multiple of `BoundedDiff.ReviewThreshold` = 128 KB). On truncation,
   append the existing marker shape
   (`\n\n[cut after N bytes — narrow the request]` for diffs a caller shows;
   for review diffs the omitted files are still named via `changedFileStats`
   → `BoundedDiff`'s trailer).
2. Give `withNewFileContents` a budget and stop rendering further untracked
   files once accumulated length passes it, emitting the existing
   `# skipped <path>: …` line for the rest:

   ```scala
   private def withNewFileContents(
       since: String,
       untracked: List[String],
       budget: Int = OsGitTool.MaxReadBytes
   ): String
   ```
3. In `untrackedFileDiff`, treat the cannot-read case (exit ≠ 0/1 with
   non-empty stderr) as `s"# skipped $relPath: no longer readable\n"` instead
   of `fail(...)`.
4. Apply the same `callCapped` routing to `diffVsBase` and
   `diffBranchExcludingOrca` (see also finding 05, which removes the need to
   materialize the latter at all).

Tests to add (`tools/src/test/scala/orca/tools/OsGitToolTest.scala`):
`reviewDiff`/`pendingChanges` over a synthetic multi-MB untracked file assert
the result length is bounded and carries the truncation marker; a
many-untracked-files case asserts later files appear as `# skipped` lines;
the current suite tests boundedness only for `show`/`fileAt`
(OsGitToolTest.scala:919-953).

Must NOT change: the `.orca/` exclusion pathspec, the `-z`/`--numstat`
parsing, `show`/`fileAt` behavior, and `BoundedDiff`'s downstream caps
(they bound the prompt; this finding bounds the heap).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; implementation made concrete).

Checked GitTool.scala:727-843/863-867/1032-1041, QuietProc.scala:29-44/56-80, Flow.scala:161, ReviewDiffSource.scala:38-40, BoundedDiff.scala:28/44, OsGitToolTest.scala:919-953. Empirically reproduced the second defect: `git diff --no-index -- /dev/null <missing>` exits 1 with non-empty stderr, so `differs` (GitTool.scala:841) is false and line 843 `fail(...)`s — a deleted-between-samples file aborts the flow. All six affected operations verified uncapped; caps are downstream-only (8 KB / 128 KB). Safe interplay confirmed: a `callCapped` truncation marker never reaches the reviewer prompt corrupted — `BoundedDiff.wholeFilesWithin` keeps only prefixes ending at `diff --git` boundaries, so the 2 MB-tail marker is dropped and omitted files are named by the trailer.

Solution revision — implementation notes: `trackedDiff` cannot reuse `gitRead` (that returns `Either[GitReadFailed, …]`); add a private throwing capped helper next to `git(...)`, e.g. `private def gitCapped(args: String*): (String, Boolean)` routing through `QuietProc.callCapped` and calling `fail(...)` on non-zero exit. `diffStat` stays uncapped on purpose (tracked-files-only, one line per file). The budget parameter on `withNewFileContents` should be passed explicitly at its three call sites (`reviewDiff`, `reviewChanges`, `pendingChanges`) rather than defaulted. No missed callers: `trackedDiff`'s callers are `diff()` and `withNewFileContents`; `withNewFileContents`'s are exactly the three composites; `OpenPrFromBranchTest`'s `RecordingGit` overrides `diffVsBase` itself, unaffected.

Ordering: item 4's capping of `diffBranchExcludingOrca` is superseded by finding 05 (which replaces the operation with an exit-code probe) — if 05 lands first, drop that part. Implement after 03/04 reshape the trait (their deletions shrink what needs capping), or accept mechanical test ports.
