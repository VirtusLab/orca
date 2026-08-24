# A finding the fixer declines (or the gate rejects) and later fixes is still reported as ignored, and later reviewers are told it is still declined

**Aspect**: correctness  **Severity**: high

## Problem

The loop's carried state only ever grows; nothing prunes an entry whose finding is later fixed.

`flow/src/main/scala/orca/review/ReviewLoop.scala:791-795` — the continue branch:

```scala
// Bound once: the accumulated set IS the cross-round decline set on
// this branch, since only declines reach it before an exit.
val carried = recordIgnored(accumulated, outcome.ignored)
loop(carried, iteration + 1, round.state, carried.issues)
```

`outcome.fixed` is never consulted. `recordIgnored` (ReviewLoop.scala:101-109) delegates to `GateLedger.mergeLatestByTitle`, which refreshes and appends but never removes. `GateLedger.record` (GateLedger.scala:21-32) is likewise a monotone union.

Trace A (declines): round 1, fixer declines X → `carried` holds X. Round 2, the reviewer re-reports X — exactly what `declinedBlock` invites ("If you still think a finding is real, report it again", ReviewLoopPrompts.scala:130-133) — and the fixer fixes it (`fixed = [X]`, loop continues). Round 3: reviewers receive `declined = carried.issues`, which still asserts "The fixer declined to fix … X" — now false. Round 3 comes back clean → `LoopStep.Done` (ReviewLoop.scala:770-772) returns `recordIgnored(accumulated, gated.issues)`, and X is reported in the final `IgnoredIssues` with the round-1 decline reason although it was fixed. This inverts the scaladoc contract at ReviewLoop.scala:284-287 ("Nothing still open is lost at any exit"): something *not* open is reported as open.

Trace B (gate): round 1, X gated at confidence 0.3 → ledger records it. Round 2, re-reported at 0.9 → admitted, handed to the fixer, fixed. Round 3 clean → `gateRejectsOf(round.state)` (ReviewLoop.scala:685-688) folds X into the result as "below the Warning confidence gate (0.3 < 0.6)" although the loop itself watched it get fixed. `GateLedger`'s scaladoc (GateLedger.scala:9-13) sanctions a bias only for "a reject whose issue later disappeared"; admitted-and-fixed is not that case.

The same staleness exists in the simple `fixLoop` (ReviewLoop.scala:166): `accumulated` is never pruned by `outcome.fixed`.

A contributing structural cause: `accumulated` is aliased as the cross-round decline set (the comment at 792-793), an invariant enforced only by the three exit arms never folding gate rejects on the continue path — nothing in the types holds it.

Existing tests bracket but do not cover this: "a fixer verdict on a once-gated finding beats its gate reason" (ReviewAndFixTest.scala:336, gated-then-*declined*) and "a finding declined in two rounds is recorded once" (ReviewAndFixTest.scala:612, declined-then-declined). Declined-then-**fixed** and gated-then-**fixed** are untested and mishandled.

## Proposed solution

Prune fixed titles from everything carried forward, at the one place a fix verdict is observed — the `NeedsFix` continue branch of `ReviewFixLoop.run` (ReviewLoop.scala:791-795):

```scala
case LoopStep.NeedsFix =>
  val outcome = FixOutcome.reconcile(issues, fix(issues))
  announceFixTurn(outcome)(using ctx)
  if outcome.fixed.isEmpty then ...   // unchanged
  else
    val fixedTitles = outcome.fixed.toSet
    val pruned = IgnoredIssues(
      accumulated.issues.filterNot(i => fixedTitles.contains(i.title)))
    val carried = recordIgnored(pruned, outcome.ignored)
    loop(carried, iteration + 1,
      round.state.copy(gateLedger = round.state.gateLedger.remove(fixedTitles)),
      carried.issues)
```

Add to `GateLedger` (flow/src/main/scala/orca/review/GateLedger.scala):

```scala
/** Every owner's entries with the given titles removed — called only when the
  * loop observed a fix verdict for those titles, so removal never happens on
  * an agent's mere silence.
  */
def remove(titles: Set[Title]): GateLedger
```

Apply the same `accumulated` prune in `fixLoop`'s recurse arm (ReviewLoop.scala:166).

Update `GateLedger`'s scaladoc: the monotone-union sentence stays true for `record`; add one sentence that `remove` is driven only by an observed fixed verdict. Update the `recordIgnored` / loop-`declined` comments (ReviewLoop.scala:93-99, 748-757, 792-793): after pruning, the "accumulated set IS the decline set" alias remains valid *and* fresh — reword the comment to say declines are pruned when their finding is fixed.

Document the trade-off in a comment at the prune site: a fixer that *falsely* claims a fix drops the entry from the record unless re-reported — the reviewer's persistent session re-reporting a still-real finding is the same recovery path the loop already relies on for `unaccounted` titles on the continue path (FixOutcome.scala:24-30).

Tests to add (flow/src/test/scala/orca/review/ReviewAndFixTest.scala, and FixLoopTest.scala for `fixLoop`):
- declined-then-re-reported-then-fixed: the title is absent from the final `IgnoredIssues`, and the round-3 reviewers' prompt does not name the round-1 decline.
- gated-then-admitted-then-fixed: the title is absent from the final `IgnoredIssues`.
- `fixLoop`: a title declined in round 1 and fixed in round 2 is absent from the result.

Must NOT change: `record`'s monotone behaviour on agent silence (removal only on a fixed verdict, never on an agent not re-reporting); the `unaccounted` continue-path behaviour (FixOutcome.scala:24-30); the two existing bracketing tests must keep passing.

## Verification

**Verdict: CONFIRMED.**

Checked ReviewLoop.scala (continue branch 791-795, `recordIgnored` 101-109, contract scaladoc 284-286, exits 770-790, `gateRejectsOf` 685-688, `fixLoop` 157-166), GateLedger.scala (record 21-32, scaladoc 8-13), ReviewLoopPrompts.scala:127-133, FixOutcome.scala:23-30, ReviewAndFixTest.scala:336/611. All factual claims hold: `outcome.fixed` is never consulted on the continue path, both traces play out as described, `GateLedger` is a monotone union, and the two cited tests bracket but do not cover the fixed cases. Solution removes the root cause at the single point a fix verdict is observed; removal fires only on titles actually handed to the fixer (reconcile drops unresolved echoes). Trusted-but-fallible framing is respected.

Implementation note: `ReviewLoop.scala` is CC-compiled and `run`'s body has both `fc: FlowControl` and `ctx: FlowContext` in scope; follow the file's existing pattern of passing `ctx` explicitly if any new helper takes `(using FlowContext)`.

Ordering: land before finding 07 (its comment rewrite at ReviewLoop.scala:748-757 subsumes part of 07's item 2), and composes with 02's `conclude` (01 prunes on the continue branch, 02 folds at exits).
