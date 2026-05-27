package orca.tools.claude

import orca.llm.{AutoApprove, BackendTag, LlmConfig}
import orca.events.{Usage}
import orca.{OrcaFlowException, OrcaInteractiveCancelled}
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

  test(
    "streaming content_block_start + input_json_delta + stop emits one AssistantToolCall"
  ):
    // Pins the per-tool-call streaming path: the agent's tool invocation
    // surfaces as soon as `content_block_stop` arrives — well before the
    // end-of-turn assistant message — so the UI doesn't batch a turn's
    // worth of tool calls together. Input args are reassembled from the
    // partial json deltas.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"id-1","name":"Bash","input":{}}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":\"l"}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"s\"}"}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_stop","index":0}}"""
    )
    // The full-turn message arrives with the same tool_use id; the
    // streamed path already emitted, so this one must be deduped.
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
    "assistant turn with tool_use that didn't stream still emits (fallback path)"
  ):
    // If `--include-partial-messages` is off or claude didn't stream the
    // tool_use as a separate content block, the full-turn message is the
    // only place we see it. Emit from there as a fallback.
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

  test(
    "askUserBridge: questions surface as UserQuestion events; respond unblocks ask"
  ):
    import ox.{forkUser, supervised}
    import ox.channels.BufferCapacity
    import orca.tools.claude.mcp.AskUserBridge
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val bridge = new AskUserBridge
      val conv = new ClaudeConversation(
        process,
        LlmConfig.default,
        askUserBridge = Some(bridge)
      )
      assert(conv.canAskUser, "canAskUser must be true when a bridge is wired")

      // From a separate fork, ask the bridge — simulates the MCP handler.
      val askResult = forkUser:
        bridge.ask("What's your favourite colour?")

      // The conversation's drainer thread should surface the question on
      // events; respond closure unblocks the ask fork.
      val firstEvent = conv.events.next()
      val (question, respond) = firstEvent match
        case ConversationEvent.UserQuestion(q, r) => (q, r)
        case other => fail(s"expected UserQuestion; got: $other")
      assertEquals(question, "What's your favourite colour?")
      respond("magenta")
      assertEquals(askResult.join(), "magenta")

      // Wind the session down so the test exits cleanly.
      process.enqueueStdout(
        """{"type":"result","subtype":"success","session_id":"sid-q"}"""
      )
      process.closeStdout()
      val _ = conv.events.toList
      val _ = conv.awaitResult()

  test("canAskUser is false when no bridge is provided"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)
    assertEquals(conv.canAskUser, false)
    process.closeStdout()
    val _ = conv.events.toList

  test(
    "streaming ask_user is suppressed at content_block_start (no AssistantToolCall)"
  ):
    // Pins the streaming-path ask_user branch in `translateStreamEvent`: a
    // tool_use content block for the MCP-prefixed ask_user tool must NOT
    // surface as an AssistantToolCall — the host-side bridge raises a
    // UserQuestion for the same exchange and rendering the tool call on
    // top would be noise. The full-turn path has its own test below; this
    // pins the streaming-path equivalent so a regression that only drops
    // the streaming branch isn't masked.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      s"""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_s","name":"${ClaudeBackend.AskUserToolName}","input":{}}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_stop","index":0}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-au"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      !events.exists(_.isInstanceOf[ConversationEvent.AssistantToolCall]),
      s"streamed ask_user must not surface as AssistantToolCall; got: $events"
    )
    val _ = conv.awaitResult()

  test(
    "turn with streamed tool_use + un-streamed text falls back to AssistantTextDelta"
  ):
    // `deltasSinceTurnBoundary` is now scoped to text/thinking deltas. If
    // it were set by tool-use stops too, the same turn's un-streamed text
    // block would be wrongly suppressed by the fallback gate in
    // `handleAssistantTurn`. Pin the narrow scoping.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"id-t","name":"Bash","input":{}}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":\"ls\"}"}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_stop","index":0}}"""
    )
    // The full turn carries the same tool_use AND a text block that did
    // not stream as deltas. The text block must fall back to a TextDelta.
    process.enqueueStdout(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"id-t","name":"Bash","input":{"cmd":"ls"}},{"type":"text","text":"running it now"}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-mix"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      events.contains(ConversationEvent.AssistantTextDelta("running it now")),
      s"un-streamed text in a mixed turn must fall back to TextDelta; got: $events"
    )
    val _ = conv.awaitResult()

  test(
    "handleAssistantTurn suppresses the agent's ToolUse for ask_user"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, LlmConfig.default)

    // Assistant turn carrying a tool_use block for the MCP-prefixed
    // ask_user tool name. Our renderer-side suppression should drop the
    // AssistantToolCall event but leave AssistantTurnEnd.
    process.enqueueStdout(
      s"""{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"tu_1","name":"${ClaudeBackend.AskUserToolName}","input":{"question":"x"}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-tu"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      !events.exists(_.isInstanceOf[ConversationEvent.AssistantToolCall]),
      s"ask_user ToolCall should have been suppressed; got: $events"
    )
    assert(
      events.exists(_ == ConversationEvent.AssistantTurnEnd),
      s"expected AssistantTurnEnd; got: $events"
    )
    val _ = conv.awaitResult()
