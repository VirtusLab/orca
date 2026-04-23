package orca.tools.claude

import orca.{
  ApprovalDecision,
  AutoApprove,
  Backend,
  ConversationEvent,
  LlmConfig,
  OrcaEvent,
  OrcaInteractiveCancelled,
  Usage
}
import orca.subprocess.FakePipedCliProcess

import java.util.concurrent.atomic.AtomicReference

class ClaudeConversationTest extends munit.FunSuite:

  private def emitSink: (AtomicReference[List[OrcaEvent]], OrcaEvent => Unit) =
    val ref = new AtomicReference[List[OrcaEvent]](Nil)
    val emit: OrcaEvent => Unit = e =>
      val _ = ref.updateAndGet(e :: _)
    (ref, emit)

  test("stream_event text_delta becomes AssistantTextDelta"):
    val process = new FakePipedCliProcess()
    val (_, emit) = emitSink
    val conv = new ClaudeConversation(process, LlmConfig.default, emit)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-1"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assertEquals(events, List(ConversationEvent.AssistantTextDelta("hello")))
    val _ = conv.awaitResult()

  test("result message finishes the session and surfaces usage"):
    val process = new FakePipedCliProcess()
    val (emitted, emit) = emitSink
    val conv = new ClaudeConversation(process, LlmConfig.default, emit)

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-2","result":"done","usage":{"input_tokens":5,"output_tokens":7}}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val result = conv.awaitResult()
    assertEquals(result.output, "done")
    assertEquals(result.usage, Usage(5L, 7L, None))
    assert(emitted.get().exists {
      case OrcaEvent.TokensUsed(_, u) => u == Usage(5L, 7L, None)
      case _                          => false
    })

  test("cancel throws OrcaInteractiveCancelled from awaitResult"):
    val process = new FakePipedCliProcess()
    val (_, emit) = emitSink
    val conv = new ClaudeConversation(process, LlmConfig.default, emit)

    conv.cancel()
    // process.sendSigInt closes stdout; awaitResult should unblock.
    intercept[OrcaInteractiveCancelled](conv.awaitResult())
    assertEquals(process.sigIntCount, 1)

  test(
    "can_use_tool with autoApprove=All responds allow without emitting an event"
  ):
    val process = new FakePipedCliProcess()
    val (_, emit) = emitSink
    val conv = new ClaudeConversation(
      process,
      LlmConfig.default.copy(autoApprove = AutoApprove.All),
      emit
    )

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"cmd":"ls"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-3"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assertEquals(events, Nil)
    val _ = conv.awaitResult()
    assertEquals(process.writes.size, 1)
    assert(
      process.writes.head.contains(""""behavior":"allow""""),
      s"expected allow response, got: ${process.writes.head}"
    )

  test(
    "can_use_tool with autoApprove=Only not matching emits ApproveTool for the channel"
  ):
    val process = new FakePipedCliProcess()
    val (_, emit) = emitSink
    val conv = new ClaudeConversation(
      process,
      LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set("Read"))),
      emit
    )

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"cmd":"rm"}}}"""
    )

    // Consume the ApproveTool event; simulate the channel denying.
    val firstEvent = conv.events.next()
    firstEvent match
      case ConversationEvent.ApproveTool(name, _, respond) =>
        assertEquals(name, "Bash")
        respond(ApprovalDecision.Deny(Some("too risky")))
      case other => fail(s"expected ApproveTool, got $other")

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-4"}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val _ = conv.awaitResult()

    val denyLine = process.writes.find(_.contains(""""behavior":"deny""""))
    assert(denyLine.isDefined, s"expected deny response; writes: ${process.writes}")
    assert(denyLine.get.contains("too risky"))

  test("sendUserMessage writes a stream-json user turn to stdin"):
    val process = new FakePipedCliProcess()
    val (_, emit) = emitSink
    val conv = new ClaudeConversation(process, LlmConfig.default, emit)

    conv.sendUserMessage("keep going")
    val injected = process.writes.headOption
    assert(injected.isDefined, "expected a stdin write")
    assert(injected.get.contains(""""type":"user""""))
    assert(injected.get.contains(""""text":"keep going""""))

    // Clean shutdown so awaitResult doesn't hang the test forever.
    process.enqueueStdout("""{"type":"result","subtype":"success","session_id":"sid-5"}""")
    process.closeStdout()
    val _ = conv.awaitResult()

  test("assistant turn with tool_use blocks emits AssistantToolCall + TurnEnd"):
    val process = new FakePipedCliProcess()
    val (_, emit) = emitSink
    val conv = new ClaudeConversation(process, LlmConfig.default, emit)

    process.enqueueStdout(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"id-1","name":"Bash","input":{"cmd":"ls"}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-6"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantToolCall("Bash", """{"cmd":"ls"}"""),
        ConversationEvent.AssistantTurnEnd
      )
    )
    val _ = conv.awaitResult()
