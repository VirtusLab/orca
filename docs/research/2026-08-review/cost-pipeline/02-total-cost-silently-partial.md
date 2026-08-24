# `Total:` silently under-reports when any turn has no resolvable cost

**Aspect**: correctness  **Severity**: medium

## Problem

`CostTracker.totalCost` (`flow/src/main/scala/orca/events/CostTracker.scala:80-81`) sums only the
buckets that have a cost:

```scala
def totalCost: Option[Cost] =
  state.get().byAgent.values.flatMap(_.cost).reduceOption(_ + _)
```

A turn whose backend reports no cost (codex and gemini emit `cost = None` on the wire) and whose
model misses the pricing table (unlisted model id, or `model = None` with no pin) resolves to
`cost = None` in `Pricing.resolve`. Its tokens still land in every bucket, but its dollars are
absent from `totalCost`. The summary (`CostTracker.scala:145-150`) then prints `Total: $X` with no
qualifier — and if the priced turns were all backend-reported, there is no asterisk and no legend
either. The summary's own convention (asterisk + legend mark uncertain figures) trains the reader to
take an unmarked `Total` as the run's full spend; here it is a partial sum with nothing marking the
partiality. The per-line omission is documented (`perAgentCost` scaladoc, lines 87-89); the total's
label is not.

Concrete scenario: a run mixing claude (reported cost) with a gemini model absent from the table
prints the gemini line with tokens and no `($…)`, then an unmarked `Total: $X` covering only the
claude spend.

## Proposed solution

In `CostTracker.State`, track whether any recorded `TokensUsed` arrived with `cost = None` — one
boolean field (e.g. `anyUnpriced: Boolean = false`), set in `record` when `cost.isEmpty`. In
`summary`, when the flag is set and a total is printed, qualify the line — e.g.
`Total (some turns unpriced): $X` / `Estimated total (some turns unpriced): $X` — or add a legend
line reusing the existing legend mechanism (`CostTracker.scala:151-156`), naming the next action:
"add the model to the pricing table via flow(pricing = …)".

Tests to add in `flow/src/test/scala/orca/CostTrackerTest.scala`:
1. One priced turn + one turn with `cost = None` → the qualifier appears and the dollar figure
   equals the priced turn's amount alone.
2. All turns priced → plain `Total:` with no qualifier (pins that the flag never false-positives).

Must NOT change: `totalCost`'s return type and summation (partial sum is correct — inventing dollars
would be worse), the per-line rendering, and the asterisk/estimate semantics.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; made concrete).

Checked CostTracker.scala:80-81 (quote exact), the unmarked `Total` at :145-150, the legend gated on `hasEstimate` at :151-156, `perAgentCost` scaladoc :87-89; "codex and gemini emit `cost = None`" verified (codex InboundEvent.scala:133; gemini has no cost decode). A bucket mixing priced and unpriced turns has `Tally.cost = Some(...)`, so the per-turn flag is genuinely needed — no existing state derives it.

Solution revision — concretely: add `anyUnpriced: Boolean = false` to `CostTracker.State` (internal state; its sibling fields already default), set in `record` when `cost.isEmpty`. In `summary`, when `anyUnpriced` and a total is printed, render the label as `Total (some turns unpriced)` / `Estimated total (some turns unpriced)`, and append one legend-style line only in that case: some turns had neither a reported cost nor a pricing-table row — add the model via `flow(pricing = …)`.

Ordering: touches CostTracker.scala/CostTrackerTest.scala, same files as findings 04 and 12 — sequence, don't parallelize.
