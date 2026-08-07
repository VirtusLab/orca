package orca.tools.claude

import orca.agents.{AutoApprove, AgentConfig, Model}
import orca.events.TurnDebit
import orca.events.Usage
import orca.testkit.Usages.usage
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}
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
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-1"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    // The delta opens a turn; `result:success` settles it, and the base-class
    // auto-close injects the owed AssistantTurnEnd.
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("hello"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest("result message finishes the session and carries usage"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-2","result":"done","usage":{"input_tokens":5,"output_tokens":7}}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "done")
    assertEquals(result.usage, usage(5L, 7L))

  // Nothing on the wire reports how many requests a turn made. The CLI splits
  // one model response into several `assistant` messages sharing one id, and a
  // response can carry several tool calls, so neither message count nor tool
  // count is the answer — only the distinct ids are.
  convTest("a turn's API-call count is its distinct response ids"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    // One response, split into prose and two parallel tool calls...
    process.enqueueStdout(
      """{"type":"assistant","message":{"id":"msg_a","role":"assistant","content":[{"type":"text","text":"checking"}]}}"""
    )
    process.enqueueStdout(
      """{"type":"assistant","message":{"id":"msg_a","role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}},{"type":"tool_use","id":"t2","name":"Read","input":{}}]}}"""
    )
    // ...then a second response.
    process.enqueueStdout(
      """{"type":"assistant","message":{"id":"msg_b","role":"assistant","content":[{"type":"text","text":"done"}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-calls","result":"done","usage":{"input_tokens":9,"output_tokens":4}}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.usage.apiCalls, Some(2L))

  convTest(
    "is_error after streaming deltas emits a short marker, not a duplicate"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

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
    // The delta opened a turn; the out-of-band is_error settles via `failWith`,
    // whose base-class auto-close injects the owed AssistantTurnEnd — so the
    // settled-failure sequence is grammar-clean.
    assertEquals(events.last, ConversationEvent.AssistantTurnEnd)
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val failure = intercept[OrcaFlowException](conv.awaitResult())
    assert(
      failure.getMessage.contains("400 quota exceeded"),
      s"awaitResult should still carry the full body; got: ${failure.getMessage}"
    )

  convTest(
    "result message with is_error=true fails the session and surfaces the message"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

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

  // The empty-body case is where the CLI puts the whole reason in `subtype`
  // (a resume replaying a queued pseudo-turn, an exhausted turn budget, …), so
  // it must stand in for the message rather than leaving a bare marker.
  convTest("is_error with an empty body names the result subtype"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      """{"type":"result","subtype":"error_max_turns","session_id":"sid-empty","is_error":true}"""
    )
    process.closeStdout()

    val errors = conv.events.toList.collect {
      case ConversationEvent.Error(msg) => msg
    }
    assertEquals(
      errors,
      List("claude reported is_error (subtype error_max_turns)")
    )
    val failure = intercept[OrcaFlowException](conv.awaitResult())
    assertEquals(
      failure.getMessage,
      "claude session failed (subtype error_max_turns, session sid-empty): " +
        "claude reported is_error (subtype error_max_turns)"
    )

  // The failing turn's tokens are on the wire in the same `result` frame; the
  // exception is the only way they can still reach the run's cost summary.
  convTest("is_error carries the result's debit on the thrown failure"):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      """{"type":"result","subtype":"error_during_execution","session_id":"sid-cost","is_error":true,"usage":{"input_tokens":11,"output_tokens":3,"cache_read_input_tokens":7},"total_cost_usd":0.25,"model":"claude-sonnet-4-6"}"""
    )
    process.closeStdout()

    val _ = conv.events.toList
    val failure = intercept[AgentTurnFailed](conv.awaitResult())
    assertEquals(
      failure.debit,
      TurnDebit.Observed(
        // The wire's counts, verbatim: claude's `input_tokens` excludes the
        // cache categories, so it is the fresh axis.
        Usage(
          freshInputTokens = 11L,
          cacheReadInputTokens = 7L,
          cacheWriteInputTokens = 0L,
          outputTokens = 3L,
          reasoningOutputTokens = 0L,
          cost = Some(BigDecimal("0.25")),
          apiCalls = None
        ),
        Some(Model("claude-sonnet-4-6"))
      )
    )

  convTest(
    "is_error after a tool-only turn (no assistant text) surfaces the full error body"
  ):
    // The deltasSinceLastFullTurn-vs-turnIsOpen distinction (see that field's
    // scaladoc): the ToolResult below makes turnIsOpen true, but no text/
    // thinking delta ever streamed, so is_error must NOT collapse into the "see
    // message above" marker — there IS no message above.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"id-1","name":"Bash","input":{"cmd":"ls"}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"id-1","content":"output","is_error":false}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"sid-tool-err","result":"API Error: 500 internal error","is_error":true}"""
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
      errors.head.contains("500 internal error"),
      s"expected the full error body, not the marker; got: ${errors.head}"
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val failure = intercept[OrcaFlowException](conv.awaitResult())
    assert(failure.getMessage.contains("500 internal error"))

  convTest(
    "cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

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
    process
      .closeStdin() // as ClaudeBackend leaves it, before any control request
    val conv = new ClaudeConversation(
      process,
      AgentConfig().copy(autoApprove = AutoApprove.All)
    )

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"cmd":"ls"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-3"}"""
    )
    process.closeStdout()

    // Auto-approved, so no ApproveTool for the channel. The decision can't reach
    // a closed stdin, and that is reported against its request instead of
    // surfacing as a parse failure.
    assertEquals(
      conv.events.toList
        .collect { case ConversationEvent.Error(m) => m }
        .count(_.contains("req-1")),
      1
    )
    val _ = conv.awaitResult()
    assertEquals(process.writes, Nil)

  convTest(
    "can_use_tool with autoApprove=Only not matching emits ApproveTool for the channel"
  ):
    val process = new FakePipedCliProcess()
    process.closeStdin()
    val conv = new ClaudeConversation(
      process,
      AgentConfig().copy(autoApprove = AutoApprove.Only(Set("Read")))
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

    val rest = conv.events.toList
    val _ = conv.awaitResult()

    // The channel's decision can't reach a closed stdin; that is reported
    // against the request it belongs to, not thrown into the reader.
    assert(
      rest.exists:
        case ConversationEvent.Error(m) => m.contains("req-2")
        case _                          => false
      ,
      rest
    )

  convTest(
    "tool_use surrounding streaming events are ignored; emission comes from the full-turn message"
  ):
    // In claude's live protocol the `assistant` message arrives BEFORE the
    // matching `content_block_stop`, so the full-turn message is the single
    // source of truth for tool calls. Streaming events for a tool_use block
    // (start/json_delta/stop) accumulate no state and produce no events —
    // emitting from them would duplicate the full-turn emit. Feed the streaming
    // events, then the full-turn message, and expect exactly one
    // AssistantToolCall.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

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
    val conv = new ClaudeConversation(process, AgentConfig())

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
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"id-1","content":"output","is_error":false}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-tr"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    // The ToolResult opens a turn (a tool ran); `result:success` settles it and
    // the base-class auto-close injects the owed AssistantTurnEnd.
    assertEquals(
      events,
      List(
        ConversationEvent.ToolResult(
          toolName = None,
          ok = true,
          content = "output"
        ),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest(
    "malformed NDJSON line surfaces as ConversationEvent.Error and the loop continues"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

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

  convTest(
    "autoApprove.Only matches the tool → no ApproveTool for the channel"
  ):
    val process = new FakePipedCliProcess()
    process.closeStdin()
    val conv = new ClaudeConversation(
      process,
      AgentConfig().copy(autoApprove = AutoApprove.Only(Set("Read")))
    )

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-ok","request":{"subtype":"can_use_tool","tool_name":"Read","input":{"path":"/etc/hosts"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-only"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(!events.exists(_.isInstanceOf[ConversationEvent.ApproveTool]))
    assert(
      events.exists:
        case ConversationEvent.Error(m) => m.contains("req-ok")
        case _                          => false
      ,
      events
    )
    val _ = conv.awaitResult()

  convTest(
    "multiple back-to-back ApproveTool events carry distinct respond closures"
  ):
    val process = new FakePipedCliProcess()
    process.closeStdin()
    val conv = new ClaudeConversation(
      process,
      AgentConfig().copy(autoApprove = AutoApprove.Only(Set.empty))
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
    val rest = conv.events.toList
    val _ = conv.awaitResult()

    // Each closure carries its own request id: the two undeliverable reports
    // name req-A and req-B separately, so the closures don't alias.
    val reported = rest.collect { case ConversationEvent.Error(m) => m }
    assert(reported.exists(_.contains("req-A")), reported)
    assert(reported.exists(_.contains("req-B")), reported)

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
        AgentConfig(),
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
    val conv = new ClaudeConversation(process, AgentConfig())
    assertEquals(conv.canAskUser, false)
    process.closeStdout()
    val _ = conv.events.toList

  convTest(
    "handleAssistantTurn suppresses the agent's ToolUse for ask_user"
  ):
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    // Assistant turn carrying a tool_use block for the MCP-prefixed ask_user
    // tool name. Renderer-side suppression drops the AssistantToolCall event, so
    // this turn bears no assistant activity — the trailing AssistantTurnEnd is
    // therefore an empty turn and the base funnel drops it.
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
      !events.contains(ConversationEvent.AssistantTurnEnd),
      s"a suppressed ask_user-only turn is empty; its turn end must be dropped, got: $events"
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest(
    "structured mode suppresses the CLI-injected StructuredOutput ToolUse and its tool_result echo"
  ):
    val process = new FakePipedCliProcess()
    val conv =
      new ClaudeConversation(process, AgentConfig(), outputSchema = Some("{}"))

    // The final turn of a `--json-schema` run: the model "exits" by calling
    // the CLI-injected StructuredOutput tool with the payload as input, and
    // the SDK echoes a matching tool_result. Both are suppressed — the payload
    // reaches the caller via the result message's structured output and
    // surfaces as `OrcaEvent.StructuredResult`; rendering the exchange would
    // show the same JSON twice.
    process.enqueueStdout(
      s"""{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"tu_so","name":"${ClaudeBackend.StructuredOutputToolName}","input":{"issues":[]}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu_so","content":"ok"}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-so"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      !events.exists(_.isInstanceOf[ConversationEvent.AssistantToolCall]),
      s"StructuredOutput ToolCall should have been suppressed; got: $events"
    )
    assert(
      !events.exists(_.isInstanceOf[ConversationEvent.ToolResult]),
      s"StructuredOutput's tool_result echo should have been dropped; got: $events"
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest(
    "a tool named StructuredOutput is NOT suppressed outside structured mode"
  ):
    // The suppression is gated on `outputSchema.isDefined`: in a plain run the
    // name can only be a genuine (if unluckily named) user tool, and hiding it
    // would silently drop real activity.
    val process = new FakePipedCliProcess()
    val conv = new ClaudeConversation(process, AgentConfig())

    process.enqueueStdout(
      s"""{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"tu_so2","name":"${ClaudeBackend.StructuredOutputToolName}","input":{}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-so2"}"""
    )
    process.closeStdout()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.AssistantToolCall(name, _) =>
          name == ClaudeBackend.StructuredOutputToolName
        case _ => false
      },
      s"an unlucky user tool named StructuredOutput must stay visible in a plain run; got: $events"
    )
    val _ = conv.awaitResult()
