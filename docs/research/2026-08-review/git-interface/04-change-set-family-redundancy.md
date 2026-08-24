# Eight overlapping change-set reads; the interface keeps both the racy parts and the composites that exist to fix the race

**Aspect**: complexity  **Severity**: medium

## Problem

The change-set family is eight operations
(`tools/src/main/scala/orca/tools/GitTool.scala:330-407`): `diff`,
`diffStat`, `untrackedPaths`, `reviewDiff`, `changedFiles`,
`changedFileStats`, `reviewChanges`, `pendingChanges`. Production uses
exactly four: `reviewChanges`
(`flow/src/main/scala/orca/review/ReviewDiffSource.scala:38`),
`changedFiles` (ReviewDiffSource.scala:44), `pendingChanges`
(`flow/src/main/scala/orca/Flow.scala:161`), and `diff` as script surface.
`reviewDiff` and `changedFileStats` have no external production caller;
`diffStat` and `untrackedPaths` are called only internally by the
composites (GitTool.scala:779-785).

The composites' own docs state why offering the parts is hazardous:
"Taking the two separately samples the untracked set twice, and a file
created between them appears in one and not the other"
(GitTool.scala:394-396, repeated at 403-406). This is a non-local
invariant: a caller must *know* to prefer `reviewChanges`/`pendingChanges`
over composing the individually-offered parts, and nothing but a doc
paragraph prevents exactly the mistake the composites were added to fix.
The redundancy also costs ~80 lines of contract prose distinguishing eight
near-synonyms, and the README `git` row (README.md:181) spends ~15 lines
re-explaining them.

Related doc inaccuracy: the "ONE sample" claim holds only for the untracked
set. `reviewChanges` (GitTool.scala:746-751) runs two separate `git diff`
invocations (patch text vs `--numstat`), and `pendingChanges`
(GitTool.scala:779-785) runs three — a tracked file modified between the
invocations (e.g. a background build still writing a generated file) can
appear in the diff but not in the stats list, so a cut diff omits it
without naming it in `BoundedDiff`'s trailer — the exact failure the doc
says is prevented.

## Proposed solution

In `tools/src/main/scala/orca/tools/GitTool.scala`:

- Drop `reviewDiff` and `changedFileStats` from the trait —
  `reviewChanges().diff` and `reviewChanges().files` are the same answers,
  atomically sampled (for the untracked set). Port the ~14 `reviewDiff`
  test sites in `OsGitToolTest.scala` to `reviewChanges().diff`
  (mechanical); `changedFileStats` tests (OsGitToolTest.scala:771-791) move
  to `reviewChanges().files`.
- Make `diffStat` and `untrackedPaths` private in `OsGitTool` (they remain
  the implementation of `pendingChanges`); their direct tests fold into
  `pendingChanges` tests.
- Surviving public family:

  ```scala
  def diff(): String                                    // script-facing (finding 12)
  def changedFiles(since: Option[String] = None): List[String]
  def reviewChanges(since: Option[String] = None): ReviewSample
  def pendingChanges(): PendingChanges
  ```
- Fix the sampling docs: either narrow the claim to the untracked set
  ("one sample of the *untracked* set; tracked changes are read per
  projection"), or make it true with a single invocation
  (`git diff --patch --numstat` parsed for both projections). The doc fix
  is the honest minimum; the single-invocation refactor is optional and
  composes with finding 01's capping work.
- Shorten the README `git` row accordingly.

Estimated saving: ~60 lines in GitTool.scala plus the README row.

Must NOT change: `ReviewSample`/`PendingChanges` shapes (their consumers
`ReviewDiffSource` and `BoundedDiff.commitPayload` stay as-is), the
`.orca/` exclusion, and `changedFiles`'s from-git-not-from-text sourcing
(its doc explains why renames/binaries need it).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; missed test ports added).

Checked GitTool.scala:330-407 (eight operations), 737-751, 779-791; production callers re-verified by grep: `reviewChanges` (ReviewDiffSource:38), `changedFiles` (ReviewDiffSource:44), `pendingChanges` (Flow.scala:161), `diff` script-surface only; `reviewDiff`'s only external references are comments. The doc-inaccuracy claim verified in code: `reviewChanges` runs the patch diff and the `--numstat` diff as separate invocations, `pendingChanges` runs three — the "ONE sample" doc holds only for the untracked list.

Solution revision — additional test ports missed: the subdirectory tests at OsGitToolTest.scala:~636-660 call `diffStat()`/`untrackedPaths()` directly; port their assertions to `pendingChanges().stat` / `pendingChanges().newFiles`. The `.orca/`-exclusion tests at ~586-618 for `diffStat`/`untrackedPaths` likewise move onto `pendingChanges`. No implementor breakage (OpenPrFromBranchTest's stub uses `export underlying.*`).

Cross-area: composes with review-loop 03 (`Sampled` keeps calling `reviewChanges`/`changedFiles`, both retained) and with 01 (whose new boundedness tests must target `reviewChanges().diff`, not the dropped `reviewDiff`). If 12's `diff()` → `uncommittedDiff()` rename lands, the surviving-family listing here uses the new name.
