package orca

import java.util.concurrent.atomic.AtomicReference

class ConversationEventTest extends munit.FunSuite:

  test("ApproveTool.respond captures the channel's decision exactly once"):
    val sink = new AtomicReference[Option[ApprovalDecision]](None)
    val evt = ConversationEvent.ApproveTool(
      toolName = "Bash",
      rawInput = """{"cmd":"ls"}""",
      respond = decision => sink.set(Some(decision))
    )
    evt match
      case ConversationEvent.ApproveTool(name, input, respond) =>
        assertEquals(name, "Bash")
        assertEquals(input, """{"cmd":"ls"}""")
        respond(ApprovalDecision.Allow())
      case other => fail(s"expected ApproveTool, got $other")
    assertEquals(sink.get(), Some(ApprovalDecision.Allow()))

  test("AssistantTextDelta and AssistantThinkingDelta are distinguishable"):
    val text = ConversationEvent.AssistantTextDelta("hello")
    val thinking = ConversationEvent.AssistantThinkingDelta("ponder")
    assertNotEquals[ConversationEvent, ConversationEvent](text, thinking)

  test("ApprovalDecision.Allow and Deny carry optional payloads"):
    assertEquals(
      ApprovalDecision.Allow(Some("""{"cmd":"safe"}""")).updatedInputJson,
      Some("""{"cmd":"safe"}""")
    )
    assertEquals(
      ApprovalDecision.Deny(Some("not allowed")).reason,
      Some("not allowed")
    )
    assertEquals(ApprovalDecision.Allow().updatedInputJson, None)
    assertEquals(ApprovalDecision.Deny().reason, None)

  test("OrcaInteractiveCancelled is an OrcaFlowException"):
    val cancelled = new OrcaInteractiveCancelled()
    assert(cancelled.isInstanceOf[OrcaFlowException])
    assertEquals(cancelled.getMessage, "interactive session cancelled")
