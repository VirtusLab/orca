# `diffBranchExcludingOrca` materializes a full branch diff to answer a boolean

**Aspect**: correctness  **Severity**: medium

## Problem

`tools/src/main/scala/orca/tools/GitTool.scala:1032-1041` renders the whole
two-dot branch diff as a `String`. Its sole production caller uses only
emptiness (`runner/src/main/scala/orca/runner/FlowLifecycle.scala:1036-1040`):

```scala
git
  .diffBranchExcludingOrca(setup.startBranch, setup.featureBranch.value)
  .isBlank
```

Every successful run's teardown (`finishBranch`) pays a full-branch-diff
materialization — on a branch with a large change set, multi-MB of heap and
pipe traffic for a yes/no answer (uncapped; see finding 01).

## Proposed solution

Replace the operation with an emptiness probe reading the exit code of
`git diff --quiet` (0 = no changes, 1 = changes; any other exit throws via
the existing `fail`):

```scala
/** True when `featureBranch` differs from `startBranch` outside `.orca/` —
  * the throwaway-branch check: false means the branch carries only orca
  * bookkeeping.
  */
def branchHasChangesExcludingOrca(
    startBranch: String,
    featureBranch: String
): Boolean
```

Implementation: `gitProc(Seq("git", "diff", "--quiet",
s"$startBranch..$featureBranch") ++ OsGitTool.wholeRepoExceptOrca)`, mapping
exit 0 → `false`, 1 → `true`, else `fail`. Update FlowLifecycle.scala:1036
to `!git.branchHasChangesExcludingOrca(...)` (or invert the throwaway
condition). Update the README `git` row name.

Tests: port the two existing cases directly
(`tools/src/test/scala/orca/tools/OsGitToolTest.scala:492-517` — `.orca/`-only
difference → `false`, code difference → `true`).

Must NOT change: the `.orca/` exclusion pathspec and the throwaway-branch
semantics in `finishBranch` (branch deleted only when `BranchMode.Created`
and no substantive changes).

## Verification

**Verdict: CONFIRMED.**

Checked GitTool.scala:1032-1041 and FlowLifecycle.scala:1036-1040 (`.isBlank` on the full diff, verbatim, sole production caller confirmed by grep); tests actually at OsGitToolTest.scala:~486-517 (minor drift from cited 492-517). Empirically verified `git diff --quiet A..B -- ':(top)' ':(exclude)...'` exit semantics: 0 = same, 1 = differs (git 2.53.0). `wholeRepoExceptOrca` already carries the `--` separator, so the proposed argv is correct as written; the probe lives in OsGitTool's file, so finding 03's `private` tightening of `wholeRepoExceptOrca` still holds. Removes the root cause (materialization for a boolean); supersedes finding 01's item-4 capping for this operation.
