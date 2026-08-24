# A usage-less claude `is_error` frame emits the all-zero `TokensUsed` that `TurnDebit.Unobserved` exists to prevent

**Aspect**: correctness  **Severity**: high

## Problem

`TurnDebit`'s contract (`tools/src/main/scala/orca/events/TurnDebit.scala:5-12`) is explicit: when the
protocol gave the driver nothing to report, the debit is `Unobserved` and no `TokensUsed` is emitted,
"because an all-zero event would be indistinguishable from a measured zero".

The claude driver violates this. `parseResult` collapses an absent wire `usage` object to zeros at
decode (`claude/src/main/scala/orca/tools/claude/streamjson/InboundMessage.scala:82`):

```scala
val u = wire.usage.getOrElse(UsageWire())
```

so `Result.usage` can no longer distinguish "absent" from "measured zero". `handleResultError`
(`claude/src/main/scala/orca/tools/claude/ClaudeConversation.scala:275-283`) then unconditionally
builds an observation:

```scala
failWith(
  new AgentTurnFailed(..., TurnDebit.Observed(withApiCalls(result.usage), turnModel(result).map(Model.apply)))
)
```

A claude turn that fails before any request completes — quota exceeded, rate limited, auth failure;
exactly the usage-less `is_error` fixtures `ClaudeConversationTest` already carries — produces
`Observed(Usage.empty)`, which `TurnAccounting.failedAfterModelRan`
(`tools/src/main/scala/orca/agents/TurnAccounting.scala:39-41`) emits as an all-zero `TokensUsed`.
Downstream: a spurious `claude: 0 in, 0 out` bucket in the cost summary and a zero `CostRecord.Turn`
line in the cost log. Codex gets this right (`Unobserved` on its failure frame); pi and opencode gate
on whether tokens were actually seen; claude's `is_error` path is the one driver that fabricates an
observation.

## Proposed solution

Keep absence at the decode seam and branch on it in the error handler:

1. In `InboundMessage.scala`, add a field to `Result` recording whether the wire carried usage —
   either `usage: Option[Usage]` or, less invasively, keep `usage: Usage` and add
   `usageReported: Boolean` set from `wire.usage.isDefined` in `parseResult`. The second option
   leaves the success path (`handleResult` / `awaitResult`) untouched.
2. In `ClaudeConversation.handleResultError`, build the debit conditionally:

   ```scala
   val debit =
     if result.usageReported then
       TurnDebit.Observed(withApiCalls(result.usage), turnModel(result).map(Model.apply))
     else TurnDebit.Unobserved
   ```

   Note `wire.total_cost_usd` rides inside `usage`; an `Unobserved` debit drops it. That is correct
   per the contract — a frame with a cost but no token counts has not been seen on the wire, and if
   it ever is, the decoder should be revisited then, not defended against now.

Tests: add a `ClaudeConversationTest` case feeding a usage-less `is_error` result (reuse one of the
existing quota/rate-limit fixtures at its lines ~102/135/161) and asserting
`intercept[AgentTurnFailed].debit == TurnDebit.Unobserved`. Keep the existing case pinning that a
usage-CARRYING `is_error` still yields `Observed` with the frame's numbers.

Must NOT change: the success path's decoding, `TurnAccounting`'s emit-nothing-on-`Unobserved`
behavior (pinned by `DefaultAgentCallTest`), and the other backends' debit paths.

## Verification

**Verdict: CONFIRMED.**

Checked TurnDebit.scala:5-12 (contract verbatim), InboundMessage.scala:82 (`wire.usage.getOrElse(UsageWire())` exact), ClaudeConversation.scala:267-284 (`handleResultError` unconditionally builds `Observed`), TurnAccounting.scala:39-41. Cross-backend claims verified: codex `failedTurnDebit = TurnDebit.Unobserved` (CodexConversation.scala:257); pi gates on `turnState.usage.fold(Unobserved)` (PiConversation.scala:90-92); opencode gates similarly (OpencodeConversation.scala:179-183). Usage-less `is_error` fixtures exist at ClaudeConversationTest.scala:102/135/161; the Unobserved-emits-nothing pin lives in the claude module's DefaultAgentCallTest.scala:441. Solution (`usageReported: Boolean` variant) is precise, leaves the success path untouched, and misses no callers.
