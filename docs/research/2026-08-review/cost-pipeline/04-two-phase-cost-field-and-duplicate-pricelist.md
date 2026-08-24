# Two-phase `TokensUsed.cost` with a silent unpriced mode, and the `PriceList` passed twice

**Aspect**: complexity  **Severity**: medium

## Problem

`OrcaEvent.TokensUsed.cost` (`tools/src/main/scala/orca/events/OrcaEvent.scala:70`) is a field with
two meanings depending on where in the pipeline you stand: pre-dispatch (always `None`, by the prose
contract "Emitters leave it unset") and post-dispatch (resolved by `CostResolvingDispatcher`,
`flow/src/main/scala/orca/events/CostResolvingDispatcher.scala:14-17`). The `= None` default makes
the unpriced state the silent one: a `CostTracker` or `RunManifestWriter` registered against a
fan-out not wrapped in `CostResolvingDispatcher` (any direct `runFlow`-style or test composition)
records `cost = None` for every turn with no error anywhere.

Separately, the same `PriceList` is handed to two components on two paths
(`runner/src/main/scala/orca/flow.scala:156` and `:287`):

```scala
val costTracker = new CostTracker(pricing)          // flow.scala:156
...
val dispatcher: OrcaListener = new CostResolvingDispatcher(pricing, ...)  // flow.scala:287
```

`CostTracker` accepts a whole `PriceList` but prices nothing — its scaladoc says "`pricing` is read
for its `lastUpdated` alone" (`CostTracker.scala:12-14`). `CostTracker(myPrices)` alongside
`runFlow(pricing = Pricing.default)` compiles and produces a summary priced by one table with a
legend dated by another. Nothing but the single call site keeps the two aligned.

Nothing pins the dispatcher contract either: no test asserts that an event arriving with `cost`
already set is re-resolved (it is — `turn.copy(cost = …)` unconditionally), and "emitters leave it
unset" is enforced by nothing.

## Proposed solution

1. Narrow `CostTracker`'s constructor to what it uses: `class CostTracker(pricingAsOf: LocalDate)`
   (`flow/src/main/scala/orca/events/CostTracker.scala:21`), no default value; the legend reads
   `pricingAsOf`. A tracker that cannot hold a table cannot disagree with the dispatcher's — the
   misleading `CostTracker(myPrices)` shape stops compiling. Update the construction sites:
   `runner/src/main/scala/orca/flow.scala:156` becomes `new CostTracker(pricing.lastUpdated)`; tests
   pass a fixed `LocalDate` (`flow/src/test/scala/orca/CostTrackerTest.scala` — ~35 sites, currently
   `new CostTracker` / `new CostTracker(testTable)` — plus
   `runner/src/test/scala/orca/runner/OrcaOverridesTest.scala:278` and
   `runner/src/test/scala/flowtests/FlowCompilesTest.scala:225`).
2. Do NOT add a `CostPipeline` factory: after step 1 the one production site (`flow()`) reads
   `pricing.lastUpdated` and `pricing` from the same parameter, so a bundling abstraction has
   nothing left to guard.
3. Drop the `= None` default on `TokensUsed.cost` (`tools/src/main/scala/orca/events/OrcaEvent.scala:70`)
   so every emitter states it explicitly: `TurnAccounting.emit` gains `cost = None`; update the
   `TokensUsed` constructions in `CostTrackerTest`, `CostResolvingDispatcherTest`, `ReviewAndFixTest`,
   `OrcaOverridesTest`, `TerminalEventListenerTest`, `CostLogTest`, `RunManifestWriterTest`,
   `ManifestRoundTripTest`. Leave the other `TokensUsed` defaults alone — they're display
   attribution, not money.

Tests to add in `flow/src/test/scala/orca/CostResolvingDispatcherTest.scala`:
- an event arriving with `cost = Some(...)` is re-resolved (overwritten), pinning the
  single-resolution-point contract;
- an event whose model misses the table passes through with `cost = None`.

Must NOT change: resolution stays at the dispatch boundary (the whole point of e6926033), listeners
stay price-table-free, and `Pricing.resolve` remains the single reported-vs-estimated home.

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked OrcaEvent.scala:63-71 (`cost: Option[Cost] = None`, "Emitters leave it unset" prose at
:52-55), CostResolvingDispatcher.scala:14-17 (unconditional `turn.copy(cost = …)`), flow.scala:156
and :287-294 (both constructions fed the same `pricing` parameter), CostTracker.scala:21 + scaladoc
:12-14, CostResolvingDispatcherTest.scala (neither proposed pin exists). The problem is factually
right. The solution was revised: the original's `CostPipeline` factory was speculative generality —
production has exactly one construction site, and after narrowing the tracker's constructor there is
no table left in the tracker to diverge — so step 2 now explicitly forbids it, and the fallout list
was made concrete (the ## Proposed solution above is the revised text).

Ordering: same-file overlap with 03 (OrcaEvent.scala, TurnAccounting.scala, shared test files) and
with 02/12 (CostTracker.scala/CostTrackerTest.scala) — sequence, don't parallelize.
