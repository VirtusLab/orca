package orca.tools.claude

import orca.agents.{AutoApprove, BackendTag, AgentConfig}
import orca.events.{Usage}
import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.backend.{
  ApprovalDecision,
  ConversationEvent,
  ConversationEventConformance
}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class ClaudeConversationTest extends munit.FunSuite:

  /** `ClaudeConversation` forks its reader/stderr/ask-user workers into the
    * caller's per-turn Ox, so construction needs a `using Ox`. Run each test
    * body in a fresh supervised scope that provides it (and joins the forks on
    * exit). The ask-user test manages its own scope and stays on plain `test`.
    */
  private def convTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  convTest("stream_event text_delta becomes AssistantTextDelta"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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

  convTest("result message finishes the session and carries usage"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-2","result":"done","usage":{"input_tokens":5,"output_tokens":7}}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "done")
    assertEquals(result.usage, Usage(5L, 7L, None))

  convTest(
    "is_error after streaming deltas emits a short marker, not a duplicate"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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

  convTest(
    "result message with is_error=true fails the session and surfaces the message"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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
    // Out-of-band is_error before any assistant turn: no turn opened, so the
    // settled-failure sequence is grammar-clean with no AssistantTurnEnd.
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val failure = intercept[OrcaFlowException](conv.awaitResult())
    assert(failure.getMessage.contains("rate limited"))

  convTest(
    "cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

    conv.cancel()
    conv.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected Left(OrcaInteractiveCancelled), got: $other")
    assertEquals(process.sigIntCount, 1)

  convTest(
    "can_use_tool with autoApprove=All responds allow without emitting an event"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      AgentConfig.default.copy(autoApprove = AutoApprove.All)
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

  convTest(
    "can_use_tool with autoApprove=Only not matching emits ApproveTool for the channel"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      AgentConfig.default.copy(autoApprove = AutoApprove.Only(Set("Read")))
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

  convTest(
    "tool_use surrounding streaming events are ignored; emission comes from the full-turn message"
  ):
    // In claude's live protocol the `assistant` message arrives BEFORE
    // the matching `content_block_stop`, so the full-turn message is the
    // single source of truth for tool calls. Streaming events for a
    // tool_use block (start/json_delta/stop) accumulate no state and
    // produce no events — emitting from them would either duplicate the
    // full-turn emit (if the order is assistant→stop) or be too late
    // (if it ever flipped). Pin both: feed the streaming events, then
    // the full-turn message, and expect exactly one AssistantToolCall.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"id-1","name":"Bash","input":{}}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":\"l"}}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"s\"}"}}}"""
    )
    // Full-turn assistant message — this is where the emit comes from.
    process.enqueueStdout(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"id-1","name":"Bash","input":{"cmd":"ls"}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_stop","index":0}}"""
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

  convTest(
    "assistant turn with text falls back to an AssistantTextDelta when no partials streamed"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest("user turn with tool_result blocks emits ToolResult events"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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
          toolName = None,
          ok = true,
          content = "output"
        )
      )
    )
    val _ = conv.awaitResult()

  convTest(
    "malformed NDJSON line surfaces as ConversationEvent.Error and the loop continues"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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

  convTest("autoApprove.Only matches the tool → silent allow"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      AgentConfig.default.copy(autoApprove = AutoApprove.Only(Set("Read")))
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

  convTest(
    "multiple back-to-back ApproveTool events carry distinct respond closures"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(
      process,
      AgentConfig.default.copy(autoApprove = AutoApprove.Only(Set.empty))
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
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val askUser = AskUserSession.allocate()
      val conv = new ClaudeConversation(
        process,
        AgentConfig.default,
        askUser = Some(askUser)
      )
      val bridge = askUser.bridge
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

  convTest("canAskUser is false when no bridge is provided"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)
    assertEquals(conv.canAskUser, false)
    process.closeStdout()
    val _ = conv.events.toList

  convTest(
    "handleAssistantTurn suppresses the agent's ToolUse for ask_user"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig.default)

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
