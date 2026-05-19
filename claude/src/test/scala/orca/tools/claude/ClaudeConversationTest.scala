package orca.tools.claude

import orca.events.{Usage}
import orca.{
  AutoApprove,
  BackendTag,
  LlmConfig,
  OrcaFlowException,
  OrcaInteractiveCancelled
}
import orca.backend.{ApprovalDecision, ConversationEvent}
import orca.subprocess.FakePipedCliProcess

class ClaudeConversationTest extends munit.FunSuite:

  test("stream_event text_delta becomes AssistantTextDelta"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

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

  test("result message finishes the session and carries usage"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-2","result":"done","usage":{"input_tokens":5,"output_tokens":7}}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "done")
    assertEquals(result.usage, Usage(5L, 7L, None))

  test("is_error after streaming deltas emits a short marker, not a duplicate"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"API Error: 400 quota exceeded"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"sid-x","result":"API Error: 400 quota exceeded","is_error":true}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    val errors = events.collect { case ConversationEvent.Error(msg) => msg }
    assertEquals(
      errors.size,
      1,
      s"expected exactly one Error event; got: $errors"
    )
    assert(
      !errors.head.contains("400 quota exceeded"),
      s"the error event should not duplicate the streamed body; got: ${errors.head}"
    )
    val failure = intercept[OrcaFlowException](conv.awaitResult())
    assert(
      failure.getMessage.contains("400 quota exceeded"),
      s"awaitResult should still carry the full body; got: ${failure.getMessage}"
    )

  test(
    "result message with is_error=true fails the session and surfaces the message"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"sid-err","result":"API Error: 400 rate limited","is_error":true}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(msg) => msg.contains("rate limited")
        case _                            => false
      },
      s"expected an Error event carrying the result body; got: $events"
    )
    val failure = intercept[OrcaFlowException](conv.awaitResult())
    assert(failure.getMessage.contains("rate limited"))

  test("cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    conv.cancel()
    conv.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected Left(OrcaInteractiveCancelled), got: $other")
    assertEquals(process.sigIntCount, 1)

  test(
    "can_use_tool with autoApprove=All responds allow without emitting an event"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      LlmConfig.default.copy(autoApprove = AutoApprove.All)
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
    val conv = new ClaudeConversation(
      process,
      LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set("Read")))
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
    assert(
      denyLine.isDefined,
      s"expected deny response; writes: ${process.writes}"
    )
    assert(denyLine.get.contains("too risky"))

  test("sendUserMessage writes a stream-json user turn to stdin"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    conv.sendUserMessage("keep going")
    val injected = process.writes.headOption
    assert(injected.isDefined, "expected a stdin write")
    assert(injected.get.contains(""""type":"user""""))
    assert(injected.get.contains(""""text":"keep going""""))

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-5"}"""
    )
    process.closeStdout()
    val _ = conv.awaitResult()

  test("assistant turn with tool_use blocks emits AssistantToolCall + TurnEnd"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

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

  test(
    "assistant turn with text falls back to an AssistantTextDelta when no partials streamed"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"no-partials"}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-fallback"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("no-partials"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    val _ = conv.awaitResult()

  test("user turn with tool_result blocks emits ToolResult events"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"id-1","content":"output","is_error":false}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-tr"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.ToolResult(
          toolName = "",
          ok = true,
          content = "output"
        )
      )
    )
    val _ = conv.awaitResult()

  test(
    "malformed NDJSON line surfaces as ConversationEvent.Error and the loop continues"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout("this is not json")
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-malformed"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(msg) => msg.contains("Failed to parse")
        case _                            => false
      },
      s"expected a parse-error event; got: $events"
    )
    val _ = conv.awaitResult()

  test("autoApprove.Only matches the tool → silent allow"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set("Read")))
    )

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-ok","request":{"subtype":"can_use_tool","tool_name":"Read","input":{"path":"/etc/hosts"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-only"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assertEquals(events, Nil)
    val _ = conv.awaitResult()
    assert(process.writes.head.contains(""""behavior":"allow""""))

  test(
    "multiple back-to-back ApproveTool events carry distinct respond closures"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set.empty))
    )

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-A","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}"""
    )
    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-B","request":{"subtype":"can_use_tool","tool_name":"Read","input":{}}}"""
    )

    val first = conv.events.next()
    val second = conv.events.next()

    second match
      case ConversationEvent.ApproveTool(_, _, respond) =>
        respond(ApprovalDecision.Deny())
      case other => fail(s"expected ApproveTool, got $other")
    first match
      case ConversationEvent.ApproveTool(_, _, respond) =>
        respond(ApprovalDecision.Allow())
      case other => fail(s"expected ApproveTool, got $other")

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-parallel"}"""
    )
    process.closeStdout()
    val _ = conv.events.toList
    val _ = conv.awaitResult()

    assert(
      process.writes.exists(w => w.contains("req-A") && w.contains("allow"))
    )
    assert(
      process.writes.exists(w => w.contains("req-B") && w.contains("deny"))
    )
