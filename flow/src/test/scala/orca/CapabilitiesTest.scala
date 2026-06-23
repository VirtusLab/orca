package orca

import orca.events.EventDispatcher

/** Tests for the capability types FlowControl and InStage (ADR 0018 §2.2).
  *
  * Positive tests: verifies that FlowControl <: FlowContext (subtyping
  * satisfaction) and that the runtime can mint and pass an InStage token.
  */
class CapabilitiesTest extends munit.FunSuite:

  /** Minimal FlowControl stub for testing. Tool accessors throw so we never
    * accidentally invoke real tools in unit tests.
    */
  private class StubFlowControl(dispatcher: EventDispatcher)
      extends FlowControl:
    private def stub(name: String) =
      throw new NotImplementedError(s"$name not wired in StubFlowControl")

    lazy val claude = stub("claude")
    lazy val codex = stub("codex")
    lazy val opencode = stub("opencode")
    lazy val pi = stub("pi")
    lazy val gemini = stub("gemini")
    lazy val git = stub("git")
    lazy val gh = stub("gh")
    lazy val fs = stub("fs")
    val userPrompt = ""
    def emit(event: events.OrcaEvent): Unit = dispatcher.onEvent(event)

  private def stubCtrl: FlowControl =
    new StubFlowControl(new EventDispatcher(Nil))

  // ── Positive: subtyping ────────────────────────────────────────────────────

  test("FlowControl satisfies a using FlowContext requirement"):
    def needsCtx(using FlowContext): Boolean = true
    given FlowControl = stubCtrl
    assert(needsCtx)

  test("FlowControl can be upcast to FlowContext"):
    val ctrl: FlowControl = stubCtrl
    val ctx: FlowContext = ctrl // upcast — must compile
    val _ = ctx
    assert(true)

  // ── Positive: InStage runtime minting ─────────────────────────────────────

  test(
    "runtime code (private[orca]) can mint an InStage and pass it to a block"
  ):
    val token: InStage = InStage.unsafe
    def needsStage(using InStage): String = "in-stage"
    assertEquals(needsStage(using token), "in-stage")
end CapabilitiesTest
