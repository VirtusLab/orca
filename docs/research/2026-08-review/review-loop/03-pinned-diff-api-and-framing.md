# `initialDiff: Option[String]` hides a three-way semantic switch, and pinned diffs are framed with a stage-scope claim the code knows may be false

**Aspect**: complexity  **Severity**: medium

## Problem

Two connected issues around pinned diffs.

**API.** `reviewAndFixLoop`'s public parameter is `initialDiff: Option[String] = None` (flow/src/main/scala/orca/review/ReviewLoop.scala:335-340, folded into the ADT at 364-366), but pinning changes three coordinated behaviours, not one: reviewers get no base commit (`Pinned.base = None`, so the `baseNote` escape hatch to read pre-change files disappears); the selector's changed-file list is scraped from diff text with documented gaps (binary changes and 100%-renames missing — ReviewDiffSource.scala:64-70); and every re-review round classifies `AlreadySeen` (ReviewDiffSource is byte-identical each round — ReviewLoopPrompts.scala:198-200), so resumed reviewers are told "No new change set this round" even after the fixer edits. The internal `private[review] sealed trait ReviewDiffSource` (ReviewDiffSource.scala:12-17) exists precisely because "each owns all three answers rather than every consumer re-deciding from an `Option`" — yet the public surface is that very `Option`, and a caller discovers the divergences one surprise at a time from scaladoc scattered across three files.

**Prompt.** The initial-review template hardcodes stage-scoped framing for every diff source (flow/src/main/resources/orca/review/prompts/initial-review.md:7-9):

```
Diff (everything this task has changed since its stage began, committed or
not). Do not use `git diff HEAD` instead — it does not show work that has been
committed:
```

`Pinned`'s own scaladoc (ReviewDiffSource.scala:47-50) says the opposite: "It may describe a change set that isn't stage-base-to-working-tree, so naming the stage base would send the reviewer to the wrong history" — and the code carefully suppresses `baseNote` for exactly this reason (pinned by the test "a pinned initialDiff is not told the stage's base commit", ReviewAndFixTest.scala:949). The template's opening parenthetical makes the same wrong-history claim the suppressed `baseNote` guards against: a caller pinning a PR's `base..head` diff gets reviewers told the diff is "everything this task has changed since its stage began, committed or not".

## Proposed solution

**1. Expose the choice as an ADT.** Add a small public type (in ReviewDiffSource.scala, or a new sibling file since it needs no CC):

```scala
/** Where `reviewAndFixLoop` gets the change set under review. */
enum ReviewDiff:
  /** Sample everything the enclosing stage has produced, re-sampled each
    * round so reviewers see the fixer's edits.
    */
  case SampleFromStage

  /** A caller-pinned diff, sent as given. Consequences, all three: reviewers
    * are not told a base commit; the selector's changed-file list is scraped
    * from the diff text (binary changes and 100%-renames absent); re-review
    * rounds see no new change set, since the pinned text never changes.
    */
  case Pinned(diff: String)
```

Replace the `initialDiff: Option[String] = None` parameter with `diff: ReviewDiff = ReviewDiff.SampleFromStage`; at loop entry (ReviewLoop.scala:364-366) resolve it:

```scala
val diffSource: ReviewDiffSource = diff match
  case ReviewDiff.SampleFromStage =>
    ReviewDiffSource.Sampled(ctx.git, fc.stageBaseCommit)
  case ReviewDiff.Pinned(d) => ReviewDiffSource.Pinned(d)
```

Move the three-consequence documentation onto `ReviewDiff.Pinned` (one place), trimming the long `initialDiff` parameter doc. The internal `ReviewDiffSource` stays `private[review]` and unchanged. Orca is 0.x — update every call site (tests use `Pinned` directly); no compat shim.

**2. Make the framing source-dependent.** Add to `ReviewDiffSource` a member the prompt renders, e.g. `def diffIntro: String` — the current stage-scoped sentence for `Sampled`, a neutral `"Diff (the change set under review):"` for `Pinned`. Turn the template's hardcoded sentence into a `{{diffIntro}}` variable and pass it through `ReviewLoopPrompts.initialReview` (ReviewLoopPrompts.scala:66-82), which gains a `diffIntro: String` parameter alongside `base`.

Tests: extend the pinned-diff prompt test (ReviewAndFixTest.scala:949) to also assert the prompt does not contain "since its stage began"; add an assertion that the sampled path still does; update any test constructing `initialDiff = Some(...)` to `ReviewDiff.Pinned(...)`.

Must NOT change: the `Sampled` framing text; `baseNote` suppression for pinned diffs; `ReReviewChanges.of`'s equality-before-size ordering (pinned never reaches `TooLarge`); the selector-files scraping behaviour itself.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; solution extended with missed consumers).

Checked ReviewLoop.scala:322-340/364-366, ReviewDiffSource.scala (no CC imports; `Pinned.base = None` at 59, scraping caveats 63-70, scaladoc 46-50), ReviewLoopPrompts.scala (`initialReview` 66-82, `ReReviewChanges.of` 197-205, `AlreadySeen` 148-151), resources/orca/review/prompts/initial-review.md:7-9 (hardcoded sentence verbatim), ReviewAndFixTest.scala:949, and grepped all `initialDiff` consumers repo-wide. The framing-vs-scaladoc contradiction is real. Production code never passes `initialDiff` (only flow-module tests do), so the signature change breaks no shipped flow. `ReviewLoopPromptsTest` pins only the confidence text, so `{{diffIntro}}` won't break it.

Solution revision — also update, in the same change:
- `runner/src/main/scala/orca/exports.scala`: add `ReviewDiff` to the `export orca.review.{...}` block — flow scripts import the surface via `import orca.{*, given}` and could not otherwise name `ReviewDiff.Pinned`.
- `README.md` ~665 ("Pass `initialDiff = Some(...)` to pin it instead.") and ~670 ("A pinned `initialDiff` is sent as given.") — reword to `diff = ReviewDiff.Pinned(...)`, plus the `reviewAndFixLoop` API-table row if it lists the parameter.
- The two remaining `initialDiff` scaladoc mentions: `flow/src/main/scala/orca/review/ReviewerSelector.scala:246` and the `ReReviewChanges` scaladoc in ReviewLoopPrompts.scala (~168-169, ~198-199) — rename to the pinned-`ReviewDiff` phrasing.
- Place the new `ReviewDiff` enum in ReviewDiffSource.scala (the file is not CC-compiled; no sibling file needed).

Cross-area: composes with git-interface 01/04 — `Sampled` keeps calling `GitTool.reviewChanges`/`changedFiles`, which git-interface 04 retains; no ordering constraint.
