# `diffVsBase` output flows unbounded into the PR-summariser prompt

**Aspect**: correctness  **Severity**: high

## Problem

`openPrFromBranch` is the one orca-owned path that consumes a whole-branch
diff with no `BoundedDiff` pass at all:

- `flow/src/main/scala/orca/pr/openPrFromBranch.scala:39` —
  `diff = git.diffVsBase(git.defaultBase())`
- `flow/src/main/scala/orca/pr/summarisePr.scala:35-38` — the diff is
  interpolated whole into the prompt:

  ```scala
  val prompt =
    s"$instructions\n\n${contextBlock}Branch diff (vs base):\n\n" +
      s"```diff\n$diff\n```"
  ```
- `tools/src/main/scala/orca/tools/GitTool.scala:863-867` — `diffVsBase`
  goes through `git(...)` → `QuietProc.call`, uncapped (finding 01).

`BoundedDiff`'s own sizing comment (`flow/src/main/scala/orca/BoundedDiff.scala:36-38`)
records that the largest change set measured in this repo was 2.1 MB, which
"fits no context window at all". A PR flow on such a branch materializes
multi-MB text and then the summarise model call fails (context overflow) or
costs absurdly — after the "Push branch" stage has already run, so the flow
dies mid-PR-open on every re-run.

(`ReviewDiffSource.Pinned` — `flow/src/main/scala/orca/review/ReviewDiffSource.scala:52-60`
— has the same property for a user-pinned `initialDiff`, but there the
"sent as given" contract is documented; `openPrFromBranch` is orca's own
default path.)

## Proposed solution

Bound the diff before it reaches the prompt, without adding `GitTool` surface:

1. Add to `flow/src/main/scala/orca/BoundedDiff.scala` a small third payload:

   ```scala
   /** A branch diff bounded for the PR summariser's prompt: under
     * [[ReviewThreshold]] it is returned whole; over it, a head cut with a
     * marker — a summary survives a mid-hunk cut, unlike a review.
     */
   def prPayload(diff: String): String =
     if diff.length <= ReviewThreshold then diff
     else
       diff.take(ReviewThreshold) +
         s"\n\n[diff cut at $ReviewThreshold characters — the summary covers the leading files only]"
   ```
2. In `flow/src/main/scala/orca/pr/openPrFromBranch.scala:39`, pass
   `diff = BoundedDiff.prPayload(git.diffVsBase(git.defaultBase()))`.

Note the heap is bounded separately by finding 01 (routing `diffVsBase`
through `callCapped`); this finding bounds the prompt.

Tests: `flow/src/test/scala/orca/pr/OpenPrFromBranchTest.scala` — with the
stub `GitTool` returning a > `ReviewThreshold` diff, assert the prompt handed
to the summariser agent is <= `ReviewThreshold` plus the marker, and carries
the marker; a `BoundedDiffTest` case pins `prPayload` under/over the
threshold.

If the review-loop work lands a base-relative stats read anyway, upgrading
`prPayload` to `reviewPayload(diff, stats)` (omitted files named) is a
follow-up, not part of this fix.

Must NOT change: `summarisePr`'s no-`stripMargin` prompt assembly (its
comment explains why), the push-before-summarise stage ordering, and
`PrSummary`'s shape.

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked openPrFromBranch.scala:39, summarisePr.scala:32-38 (prompt interpolation verbatim), GitTool.scala:863-867, BoundedDiff.scala:36-38 (2.1 MB sizing comment verbatim), ReviewDiffSource.scala:52-60. The problem is fully factual and the re-run failure mode real (push stage commits, summarise stage re-materializes and re-fails).

Revision rationale: the original primary option added a new `GitTool` operation (`changedFileStatsVsBase`) whose only consumer would be the PR summariser, while findings 03/04 correctly shrink that surface — speculative surface. A head-cut suffices for title/body generation (the original conceded this as its alternative), and the bounding decision belongs in `BoundedDiff` (one decision, one home), not inline in `openPrFromBranch`. The ## Proposed solution above is the revised version.
