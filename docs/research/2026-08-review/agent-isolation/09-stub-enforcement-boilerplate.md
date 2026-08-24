# Sixteen identical enforcementCell stubs in test doubles; dead type parameters in EnforcementNotice helpers

**Aspect**: conciseness  **Severity**: low

## Problem

**(a)** Sixteen test doubles across nine files each carry the identical 6-line
block:

```scala
def enforcementCell(
    tools: ToolSet,
    autoApprove: AutoApprove,
    dispatch: TurnDispatch
): EnforcementCell =
  StubEnforcement.cell
```

Sites (verified count: 16): `tools/src/test/.../agents/BaseAgentTest.scala`
(five doubles), `DefaultAgentCallTest.scala`, `ChatTest.scala`,
`WithCheapModelTest.scala`, `AgentCallSessionCommittedTest.scala`,
`flow/src/test/.../review/FixOutcomeAnnounceTest.scala`,
`runner/src/test/.../LeadAgentIdentityTest.scala`, `OpencodeFlowTest.scala`,
`opencode/src/test/.../DefaultOpencodeToolTest.scala` — plus the
`ToolSet`/`AutoApprove`/`TurnDispatch`/`EnforcementCell` imports each file
carries only for this. That's ~70 net lines of copy-paste. Only
`BaseAgentTest.scala`'s two doubles with non-stub cells (~lines 850, 864) need
a real body.

**(b)** `EnforcementNotice`'s private helpers `summary` and `turnWording`
(`tools/src/main/scala/orca/agents/EnforcementNotice.scala:70, 121`) declare
`[B <: BackendTag]` that nothing in their bodies needs — `tag.wireName` and
`enforcementCell` don't mention `B`, so `backend: AgentBackend[?]`
type-checks. Only `announceShortfall` needs `B`, for the
`SessionId[B]`/`sessions` pairing.

## Proposed solution

**(a)** Add a mixin next to `StubEnforcement` in
`tools/src/test/scala/orca/testkit/`:

```scala
/** Backend test doubles that don't exercise enforcement mix this in
  * instead of hand-writing the Hard stub cell. */
trait StubEnforcementCell:
  def enforcementCell(
      tools: ToolSet, autoApprove: AutoApprove, dispatch: TurnDispatch
  ): EnforcementCell = StubEnforcement.cell
```

Doubles add `with StubEnforcementCell` and delete the block (and the imports it
alone needed). The parallel one-line `structuredOutputMode = RawText` stub that
most of the same doubles carry can ride in the same trait (name it
`StubBackendAnswers` or similar if it grows beyond enforcement).

Note: if finding 06 (template method) is implemented, these doubles also rename
`runAutonomous`→`doRunAutonomous`; do the two passes together to touch each
file once.

**(b)** Change `summary` and `turnWording` to take `backend: AgentBackend[?]`
and drop the type parameter.

Tests: this only touches test scaffolding and private signatures — the full
suite passing unchanged is the whole verification. Must NOT change:
`StubEnforcement.cell`'s value (a `Hard` cell, "the answer that reports
nothing") or the two BaseAgentTest doubles that declare real cells.

## Verification

**Verdict: CONFIRMED.**

Counted `StubEnforcement.cell` occurrences: exactly 16 across exactly the nine listed files (BaseAgentTest 8, the other eight files 1 each) — one non-core inaccuracy: the parenthetical "five doubles" in BaseAgentTest should read "eight" (plus the two real-cell doubles at ~lines 850/864, `UngatedBackend` and `WeakerOnResumeBackend`, correctly identified as needing real bodies). StubEnforcement.scala verified (Hard cell, "the answer that reports nothing"). Claim (b) verified: `summary`/`turnWording` (EnforcementNotice.scala:70/121) declare `[B <: BackendTag]` nothing in their bodies needs — `backend: AgentBackend[?]` type-checks; only `announceShortfall` pairs `SessionId[B]` with `sessions`. The testkit trait is reachable from all nine files (already importing `StubEnforcement` via `tools % test->test`), and a standalone trait's concrete method implements the abstract member fine.

Ordering: pair with finding 06's `runAutonomous` → `doRunAutonomous` rename so each test-double file is touched once.
