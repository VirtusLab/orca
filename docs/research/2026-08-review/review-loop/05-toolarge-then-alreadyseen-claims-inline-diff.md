# After a TooLarge round, an unchanged re-sample tells the reviewer "the diff already in this conversation" — but the conversation holds only a path list

**Aspect**: correctness  **Severity**: low

## Problem

`flow/src/main/scala/orca/review/ReviewLoop.scala:475-479` — `lastDiff` advances on any non-`AlreadySeen` classification, including `TooLarge`, where only paths were sent:

```scala
// Only advance `lastDiff` when something was actually sent, so a reviewer
// that skipped a round still compares against what it has seen.
val advanced = Option.when(changes != ReReviewChanges.AlreadySeen)(
  se.copy(lastDiff = current.diff)
)
```

`SessionEntry.lastDiff`'s own doc (ReviewLoop.scala:174-176) defines it as "the change set this reviewer was last sent" — which `TooLarge` violates: the diff text was classified, never sent. If the next round's sample is byte-identical (the fixer claimed a fix without tree edits, or its edits were reverted by `formatWorkspace`), `ReReviewChanges.of` classifies `AlreadySeen` and the reviewer gets (ReviewLoopPrompts.scala:148-151):

```scala
"No new change set this round — the diff already in this conversation " +
  "is the one under review. Check the code itself to see whether your " +
  "earlier findings still stand."
```

— pointing at an inlined diff its conversation never contained; it only ever received the path list. The fallback instruction ("Check the code itself") limits the damage, hence low severity.

## Proposed solution

Have `SessionEntry` remember *how* the last change set was delivered, and word the `AlreadySeen` message accordingly. In ReviewLoop.scala replace `lastDiff: String` with a two-case ADT (no `Boolean` flag — AGENTS.md's enum rule):

```scala
/** What the reviewer was last sent about the change set: the diff text it
  * compares against, and whether the text itself or only its paths reached
  * the conversation.
  */
private enum LastSent(val diff: String):
  case Inline(d: String) extends LastSent(d)
  case PathsOnly(d: String) extends LastSent(d)
```

`ReReviewChanges.of(se.lastSent.diff, current)` stays unchanged; when classifying `AlreadySeen`, `resumeReview` selects the message variant: the current wording for `Inline`, and for `PathsOnly` something like "No new change set this round — the file list already in this conversation still describes the change set; re-read those files." The message choice moves into `ReviewLoopPrompts.reReview` by passing the delivery mode (e.g. widen `ReReviewChanges.AlreadySeen` to carry it, or add a parameter). `firstReview` stores `Inline` (the initial diff is always inlined, possibly bounded); a `TooLarge` resume stores `PathsOnly(current.diff)`; an `Updated` resume stores `Inline(current.diff)`.

Tests (flow/src/test/scala/orca/review/, alongside the existing `ReReviewChanges` and prompt tests): a `TooLarge` round followed by a byte-identical sample produces a resume prompt that does not claim an inlined diff and does point back at the file list.

Must NOT change: advancing past `TooLarge` (it prevents re-sending the same large change set every round); `AlreadySeen` classification for pinned diffs; the `InlineThreshold` value; the equality-before-size test order in `ReReviewChanges.of`.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; plumbing made concrete).

Checked ReviewLoop.scala:474-479 (advance comment + `Option.when`, verbatim), `lastDiff` doc 174-176, ReviewLoopPrompts.scala `changesBlock` 135-151 (`TooLarge` sends paths only; `AlreadySeen` claims an inlined diff), `ReReviewChanges.of` 202-205 (the byte-identical re-sample after a `TooLarge` round is reachable). All claims hold; low severity is right given the "check the code itself" fallback.

Solution revision — the "widen `AlreadySeen` … or add a parameter" choice is resolved concretely: move `LastSent` next to `ReReviewChanges` in ReviewLoopPrompts.scala as `private[review]`; change `ReReviewChanges.of` to take `previous: LastSent` (it reads `previous.diff` for the equality test — equality-before-size order unchanged); widen the case to `AlreadySeen(last: LastSent)` so `changesBlock` selects the message variant by matching on `last`. `SessionEntry.lastDiff: String` becomes `lastSent: LastSent`; `firstReview` stores `Inline(currentDiff)`; `resumeReview`'s advance stores `PathsOnly(current.diff)` when `changes` was `TooLarge` and `Inline(current.diff)` when `Updated`. Update `ReviewChangeSetTest`'s direct `ReReviewChanges.of` call sites for the new parameter type (assertions unchanged).

Ordering: conflicts textually with finding 08 (both rewrite `SessionEntry`) — implement 08's field deletion and this retyping in one PR, or 08 first.
