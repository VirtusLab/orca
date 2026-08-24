# Two pipeline contracts have no pinning test

**Aspect**: correctness  **Severity**: low

## Problem

Two documented, load-bearing behaviors are enforced by nothing but inspection:

1. **`CostTracker` thread-safety.** The class contract says it is "safe to register across
   concurrent LLM calls" (`flow/src/main/scala/orca/events/CostTracker.scala:8-9`), and the reviewer
   fan-out emits from parallel forks. The implementation is correct by inspection
   (`AtomicReference.updateAndGet` over a pure `record`, :62-70), but `CostTrackerTest` is entirely
   sequential — a refactor to get-then-set would drop concurrent tallies and no test would notice.
   `RunManifestWriterTest` has exactly this style of test for the writer (:435-477); the tracker has
   none.
2. **`Usage.inclusiveInput`'s wire-total cross-check.** The `wireTotal` residue check added in
   e6926033 (`tools/src/main/scala/orca/events/Usage.scala:106-112`) — gemini's `total_tokens`
   against the decoded axes — is unexercised: no test passes `wireTotal = Some(...)`, neither the
   matching nor the mismatching case, so the one guard that announces a dropped wire counter is
   itself unguarded.

(The third gap found in this area — no pin on `CostResolvingDispatcher` re-resolving an already-set
`cost` — is folded into finding 04's test additions.)

## Proposed solution

1. Add a `CostTrackerTest` case mirroring `RunManifestWriterTest`'s concurrency shape: two threads,
   N `TokensUsed` events each (distinct agents so both axes are exercised), then assert
   `total` equals the exact sum and `perAgent` has both buckets complete. Ox-style forks or plain
   threads both fine — the point is concurrent `onEvent`.
2. Add two cases to the existing `tools/src/test/scala/orca/events/UsageTest.scala` (its current
   `inclusiveInput` cases all pass `wireTotal = None`): `inclusiveInput` with a
   `wireTotal` equal to the decoded sum (no residue logged — assert the returned axes), and one with
   a larger `wireTotal` (assert the axes are still returned unchanged; if asserting the debug log is
   impractical, the axis-arithmetic pin alone still covers the split-and-clamp path with `wireTotal`
   present).

Must NOT change: production code — both gaps are test-only additions.

## Verification

**Verdict: CONFIRMED.**

Checked CostTracker.scala:8-9 (thread-safety contract) and :62-70 (`AtomicReference.updateAndGet` over pure `record`); CostTrackerTest has zero threads/forks (grep) — sequential, confirmed; RunManifestWriterTest.scala:435-448 has the referenced concurrency-shape test. Usage.scala:106-112 `wireTotal` residue check confirmed; UsageTest passes `wireTotal = None` only (lines 87, 103) — both gaps real; gemini is the only `wireTotal` supplier (gemini/jsonl/InboundEvent.scala:177). Test-only, precisely scoped, implementable as written. Ordering: touches CostTrackerTest, same file as 02/04 — sequence.
