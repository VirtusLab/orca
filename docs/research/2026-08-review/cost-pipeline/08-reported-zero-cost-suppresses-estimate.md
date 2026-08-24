# A backend-reported cost of exactly 0 is authoritative and suppresses the estimate

**Aspect**: correctness  **Severity**: low

## Problem

`Pricing.resolve` (`flow/src/main/scala/orca/events/Pricing.scala:98-105`):

```scala
usage.cost
  .map(amount => Cost(amount, estimated = false))
  .orElse(estimate(table, model, usage).map(Cost(_, estimated = true)))
```

`estimate` deliberately refuses to produce a zero `Cost` (lines 108-111: "a zero estimate would be a
`Cost` whose `estimated` flag relabels the whole run's total, having priced nothing"), but the
reported path has no guard: `Some(0)` on a turn with non-zero tokens yields
`Cost(0, estimated = false)` — a "billed zero" that suppresses the table estimate and flows unmarked
into `Total`. Whether a wire actually sends `cost: 0` alongside real tokens is not verifiable from
this repo (opencode's `AssistantInfo.cost` and pi's `cost.total` are decoded whenever present); if
opencode reports `cost: 0` for a model it cannot price, or under subscription auth, the run's spend
reads as `$0.0000` non-estimated. No test pins either interpretation.

## Proposed solution

Decide the semantics once, in `Pricing.resolve`, and pin it. The safer reading: treat a reported
zero on a turn that spent tokens as "no report" and fall through to the estimate —

```scala
usage.cost
  .filter(c => c != 0 || (usage.inputTokens == 0 && usage.outputTokens == 0))
  .map(amount => Cost(amount, estimated = false))
  .orElse(estimate(table, model, usage).map(Cost(_, estimated = true)))
```

— with a comment stating the invariant ("a zero report against non-zero tokens is a backend that
couldn't price the call, not a free call"). If the maintainer prefers to trust the wire instead,
keep the current behavior but pin it explicitly. Either way, add two `Pricing`-level cases (a
`CostTrackerTest` or new `PricingTest`): `cost = Some(0)` with tokens and a table row → the chosen
outcome; `cost = Some(0)` with zero tokens → `Cost(0, estimated = false)` stays.

Ideally, live-probe opencode's zero-cost behavior (per AGENTS.md's "debugging backend breakage"
recipe) before choosing.

Must NOT change: the reported-figure-wins rule for non-zero reports, and `estimate`'s
no-zero-estimate guard.

## Verification

**Verdict: CONFIRMED-REVISED** (the "decide once … or keep current" fork resolved — too vague for a context-free implementer).

Checked Pricing.resolve at Pricing.scala:98-105 (exact), `estimate`'s no-zero guard (:107-110 comment, `Option.when(...)` at :121-122), opencode decoding `AssistantInfo.cost` (OpencodeApi.scala:94, OpencodeConversation.scala:171/193), pi decoding `cost.flatMap(_.total)` (pi/rpc/InboundEvent.scala:193/207). No `PricingTest` exists and no test pins `Some(0)` either way — confirmed. Trusted-but-fallible framing (backend can't price a call), not adversarial.

Solution revision — commit to the filter: implement the filter shown above (a reported zero against non-zero tokens falls through to the estimate). Add the two cases in a new `flow/src/test/scala/orca/PricingTest.scala`: `cost = Some(0)` with tokens and a table row → estimated `Cost`; `cost = Some(0)` with zero tokens → `Cost(0, estimated = false)`. Also keep one case pinning that a non-zero report still wins over the table. The opencode live-probe is optional follow-up, not a blocker.
