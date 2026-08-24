# `DiffMode` is an enum with one live case

**Aspect**: complexity  **Severity**: medium

## Problem

`DiffMode.Direct` is never passed anywhere: the production and shipped-flow
`diffVsBase` calls all use the default
(`flow/src/main/scala/orca/pr/openPrFromBranch.scala:39`,
`flows/issue-pr-bugfix.sc:168`). The only reference outside
`tools/src/main/scala/orca/tools/GitTool.scala` is a test stub forced to
restate the default
(`flow/src/test/scala/orca/pr/OpenPrFromBranchTest.scala:53`):

```scala
def diffVsBase(base: String, mode: DiffMode = DiffMode.MergeBase): String
```

Meanwhile the one production two-dot diff, `diffBranchExcludingOrca`
(GitTool.scala:1032-1041), builds `$startBranch..$featureBranch` inline
rather than going through the enum — so `Direct` abstracts a case that is
already handled without it. The enum + parameter + doc blocks
(GitTool.scala:13-23, 409-419, 863-867) exist for a choice nobody makes,
and every `GitTool` stub must repeat the default.

## Proposed solution

Delete `DiffMode` and the `mode` parameter; `diffVsBase(base: String)`
hardcodes the three-dot merge-base spec (`s"$base...HEAD"`), keeping the
trait doc's merge-base/GitHub-PR-view sentence. Update the
`OpenPrFromBranchTest` stub and the README `git` row. If a direct diff is
ever needed, `diffBranchExcludingOrca` (or its finding-05 successor) shows
the two-dot pattern.

~25 lines saved.

Must NOT change: three-dot semantics of `diffVsBase` (the PR-view contract
its callers rely on), `defaultBase`'s probe-with-fallback.

## Verification

**Verdict: CONFIRMED.**

Whole-repo grep for `DiffMode`: only GitTool.scala and the OpenPrFromBranchTest stub (line 53, restating the default, verbatim as quoted — the import at line 19 must also be dropped). Both production `diffVsBase` calls use the default; `diffBranchExcludingOrca` builds its two-dot spec inline at line 1040. README does not mention `DiffMode` by name. Clean deletion of speculative generality; the only implementor to update is `OsGitTool` plus the one test stub.

Ordering: 09 changes `diffVsBase`'s signature while 01 changes its body and 02 wraps its result — apply 09 first or together with 01.
