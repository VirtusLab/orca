package orca

/** Consumer-taint canary — pins the no-taint property against future compiler
  * upgrades.
  *
  * [[InStage]] extends `caps.SharedCapability` (non-experimental), while
  * [[WorkspaceWrite]] and [[FlowControl]] extend `caps.ExclusiveCapability` —
  * which IS `@experimental` on 3.8.4 — behind a `captureChecking` language
  * import in their OWN files. This file carries no such import and is not
  * `@experimental`, yet it freely mints, summons, and passes all three tokens.
  *
  * If a future compiler propagated `@experimental` from the capture-checking
  * parents into these definitions, referencing them from this plain, non-CC
  * compilation unit would fail with "... is marked @experimental", turning this
  * test RED. That taint cannot happen on stable 3.8.4; this is the explicit,
  * named insurance for it.
  */
class NoTaintCanaryTest extends munit.FunSuite:

  test("all three capability tokens are usable from a plain, non-CC unit"):
    given InStage = InStage.unsafe
    given WorkspaceWrite = WorkspaceWrite.unsafe

    def needsInStage(using InStage): Boolean = true
    def needsWorkspace(using WorkspaceWrite): Boolean = true
    // FlowControl is the exclusive capability mixed into a public trait — the
    // likeliest taint carrier — so name it in a type position too.
    val widenFlowControl: FlowControl => FlowContext = fc => fc

    assert(needsInStage, "InStage summonable/passable in a non-CC unit")
    assert(
      needsWorkspace,
      "WorkspaceWrite summonable/passable in a non-CC unit"
    )
    assert(widenFlowControl ne null)

end NoTaintCanaryTest
