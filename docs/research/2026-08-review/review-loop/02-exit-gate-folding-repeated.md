# Three exit sites each hand-fold gate rejects, with an ordering constraint each must independently respect

**Aspect**: complexity  **Severity**: medium

## Problem

`flow/src/main/scala/orca/review/ReviewLoop.scala` — every exit arm of `ReviewFixLoop.run` repeats the gate-reject fold, and one arm carries a position-sensitive rule only a comment states:

```scala
case LoopStep.Done =>
  orca.display(doneMessage(stillGated.size))
  recordIgnored(accumulated, gated.issues)                       // line 772
case LoopStep.CapReached(ignored) =>
  orca.display(capExitMessage(maxIterations, ignored))
  // Gate rejects go in first: the ledger holds them from whichever
  // round first held one back, so this round's verdict on the same
  // title is the fresher one and must win.
  recordIgnored(accumulated, gated.issues, ignored.issues)       // line 778
case LoopStep.NeedsFix => ...
    recordIgnored(accumulated, gated.issues, outcome.ignored,
      outcome.unaccounted.map(IgnoredIssue(_, NoFixesReason)))   // lines 785-789
```

`recordIgnored`'s varargs make the argument order look interchangeable, but it isn't: a future exit branch that forgets `gated.issues` or appends it last silently loses or mis-reasons gated findings, and the compiler cannot notice. Tracing "what happens to a gate-dropped finding" already spans seven structures (`applyGate` at 431-433 → `GatedIssues.dropped` → `RoundContribution` → `afterRound` at 199-213 → `GateLedger.record` → `gateRejectsOf` at 685-688 → `gatedOut` at 706-709) before reaching any of these folds. As a side effect, `stillGated`/`gated` are computed every round (lines 763-764) and discarded on the continue branch, which never uses them.

## Proposed solution

Make the exit a value and the fold one function, in `ReviewFixLoop` (ReviewLoop.scala):

```scala
private enum LoopExit:
  case Clean
  case Capped(stillOpen: IgnoredIssues)
  case FixerHalted(outcome: ReconciledFixOutcome)

/** The only place gate rejects are folded into the result; gate rejects go in
  * first so a fresher same-title verdict wins.
  */
private def conclude(
    exit: LoopExit,
    accumulated: IgnoredIssues,
    state: ReviewLoopState
): IgnoredIssues =
  val stillGated = gateRejectsOf(state)
  val gated = gatedOut(stillGated)
  exit match
    case LoopExit.Clean =>
      orca.display(doneMessage(stillGated.size))
      recordIgnored(accumulated, gated.issues)
    case LoopExit.Capped(stillOpen) =>
      orca.display(capExitMessage(maxIterations, stillOpen))
      recordIgnored(accumulated, gated.issues, stillOpen.issues)
    case LoopExit.FixerHalted(outcome) =>
      orca.display("Fixer reported no fixes; bailing out")
      recordIgnored(accumulated, gated.issues, outcome.ignored,
        outcome.unaccounted.map(IgnoredIssue(_, NoFixesReason)))
```

The loop's match arms then build a `LoopExit` and call `conclude(...)` once; the `stillGated`/`gated` vals at 763-764 move inside `conclude`, so continue rounds no longer compute them. The "gate rejects go in first" comment survives in exactly one place.

If finding 01 (pruning fixed titles) is implemented, `conclude` sits on top of it unchanged — pruning happens on the continue branch, `conclude` only at exits.

Tests: existing exit tests (`ReviewAndFixTest`: the cap exit, the gated-finding exit ordering test at line 336, the fixer-halt exit) already pin the behaviour; they must pass unchanged. No new tests required.

Must NOT change: the merge ordering (gate rejects first), the display messages, `recordIgnored` / `GateLedger.mergeLatestByTitle` semantics, and `fixLoop` (which has no gate and stays as is).

## Verification

**Verdict: CONFIRMED.**

Checked ReviewLoop.scala exits 770-790, `stillGated`/`gated` at 763-764 (computed every round, unused on the continue branch — verified), the ordering comment 775-777, and the seven-structure trace (`applyGate` 431-433, `afterRound` 199-213, `gateRejectsOf` 685-688, `gatedOut` 706-709). All claims hold. The enum + `conclude` roughly breaks even on lines but earns its place: the fold-ordering invariant lands in one commented place, and `gateRejectsOf`/`gatedOut` stop being computed on continue rounds. `conclude` needs only class fields — implementable as written; existing exit tests pin behaviour.

Ordering: implement 02 before 06 — both touch the halt arm's display. After 02, finding 06's `fixerHaltAdditions` is called from `conclude`'s `FixerHalted` arm, and the bail-out display must live in exactly one of the two helpers (put it in 06's helper; `conclude`'s `FixerHalted` arm then drops its own `orca.display` line).
