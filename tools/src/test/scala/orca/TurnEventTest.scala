package orca

import orca.backend.{ApprovalDecision, TurnEvent}
import orca.events.TurnDebit

import java.util.concurrent.atomic.AtomicReference

class TurnEventTest extends munit.FunSuite:

  test("ApproveTool.respond captures the channel's decision exactly once"):
    val sink = new AtomicReference[Option[ApprovalDecision]](None)
    val evt = TurnEvent.ApproveTool(
      toolName = "Bash",
      rawInput = """{"cmd":"ls"}""",
      respond = decision => sink.set(Some(decision))
    )
    evt match
      case TurnEvent.ApproveTool(name, input, respond) =>
        assertEquals(name, "Bash")
        assertEquals(input, """{"cmd":"ls"}""")
        respond(ApprovalDecision.Allow)
      case other => fail(s"expected ApproveTool, got $other")
    assertEquals(sink.get(), Some(ApprovalDecision.Allow))

  test("AssistantTextDelta and AssistantThinkingDelta are distinguishable"):
    val text = TurnEvent.AssistantTextDelta("hello")
    val thinking = TurnEvent.AssistantThinkingDelta("ponder")
    assertNotEquals[TurnEvent, TurnEvent](text, thinking)

  test("OrcaInteractiveCancelled is an OrcaFlowException"):
    val cancelled = new OrcaInteractiveCancelled(TurnDebit.Unobserved)
    assert(cancelled.isInstanceOf[OrcaFlowException])
    assertEquals(cancelled.getMessage, "interactive turn cancelled")
