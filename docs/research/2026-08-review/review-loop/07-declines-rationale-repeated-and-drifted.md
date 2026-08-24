# The "declines are sent / fixes are not" rationale is stated five times, and one copy is factually wrong

**Aspect**: conciseness  **Severity**: low

## Problem

The same design rationale is restated near-verbatim across two files:

- `flow/src/main/scala/orca/review/ReviewLoop.scala:278-282` (`reviewAndFixLoop` scaladoc): "The `ignored` entries go to the next round's reviewers … the one thing in the loop it could not have worked out by reading the code. The `fixed` titles do not …"
- ReviewLoop.scala:459-462 (`resumeReview` scaladoc): "The fixer's `fixed` titles are deliberately NOT sent …" — same argument again.
- ReviewLoop.scala:748-757 (the loop's `declined` parameter, a 10-line comment).
- `flow/src/main/scala/orca/review/ReviewLoopPrompts.scala:62-64` (`initialReview` scaladoc): "the fixer's refusals are the one thing it cannot recover by reading the code …"
- ReviewLoopPrompts.scala:106-107 (`reReview` scaladoc): "`declined` is what the fixer refused to fix **last round**, which is the one thing a reviewer cannot recover by reading the code."

The repetition has produced drift: `reReview`'s copy says "last round", but the loop passes the accumulated set — `loop(carried, iteration + 1, round.state, carried.issues)` (ReviewLoop.scala:795), and the parameter comment at 752-754 explicitly says "The whole set rather than the last round's". Two comments in the same package contradict each other about what `declined` contains. AGENTS.md's comment rule applies directly: "Don't restate … neighbouring comments."

Same-category items, same fix batch:
- ReviewLoop.scala:195-198 — `afterRound`'s scaladoc narrates its four-line body field by field ("this round's batch on the history, each reviewer that ran holding its latest session, and every gate reject unioned into the ledger").
- ReviewLoop.scala:142-143 and 730-731 — the "A progress marker, not a committing stage (ADR 0018 §2.2)" comment appears in both loops.

## Proposed solution

All edits in ReviewLoop.scala and ReviewLoopPrompts.scala; prose only, no behaviour change.

1. Keep ONE canonical statement of the rationale: the `reviewAndFixLoop` scaladoc (ReviewLoop.scala:278-282), the user-facing contract.
2. Reduce the copies to one-line cross-references:
   - `resumeReview` (459-462): "The fixer's `fixed` titles are deliberately not sent — see [[reviewAndFixLoop]]."
   - `initialReview` doc (ReviewLoopPrompts.scala:62-64): keep only the joining-reviewer-specific fact ("`declined` matters for a reviewer first activated after round one"), drop the repeated why.
   - `reReview` doc (106-107): "`declined` is every refusal the fixer has made so far — see [[reviewAndFixLoop]]." This also fixes the "last round" error.
   - The loop's `declined` parameter comment (748-757): keep the two file-specific facts that live nowhere else (whole set, not last round's; not split per reviewer because `FixOutcome` doesn't attribute findings) and drop the sentences repeating the scaladoc. (If finding 01 lands, this comment is being rewritten anyway — fold this in.)
3. Delete `afterRound`'s scaladoc body (195-198); the method name and four-line body carry it.
4. Keep the ADR 0018 §2.2 note once — `run`'s fuller version at 730-731 — and shrink `fixLoop`'s copy at 142-143 to nothing or a bare "(ADR 0018 §2.2)".

Tests: none — comment-only change. Must NOT change: any prompt text sent to agents (`declinedBlock` etc. are untouched — only scaladoc/comments change), and the canonical rationale's content.

## Verification

**Verdict: CONFIRMED.**

Checked all five rationale copies verbatim (ReviewLoop.scala:278-282, 460-462 — one line of drift from the cited 459-462 — and 748-758; ReviewLoopPrompts.scala:62-64, 106-107). The contradiction is real: `reReview`'s doc says "last round" while the loop passes the accumulated set (`carried.issues`, line 795) and the parameter comment says "The whole set rather than the last round's". `afterRound` scaladoc 195-198 and the duplicated ADR 0018 §2.2 note at 142-143/730-731 also verified.

Implementation notes: for item 4, use the bare "(ADR 0018 §2.2)" form on `fixLoop`'s copy rather than deleting it (`fixLoop` is a separate public entry point; the cite is one token). `runReviewersAndLint`'s scaladoc (524-525) already states the correct "every refusal … so far" phrasing — it is not one of the copies; leave it.

Ordering: implement after finding 01 — its rewrite of the 748-757 comment subsumes part of item 2.
