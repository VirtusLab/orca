package orca.tools.claude

import orca.agents.Model
import orca.events.{TurnDebit, Usage}
import orca.testkit.Usages.usage
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}
import orca.backend.{AskUserChannel, TurnEvent, TurnEventConformance}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class ClaudeTurnTest extends munit.FunSuite:

  /** `ClaudeTurn` forks its reader/stderr/ask-user workers into the caller's
    * per-turn Ox, so construction needs a `using Ox`. Run each test body in a
    * fresh supervised scope that provides it (and joins the forks on exit). The
    * ask-user test manages its own scope and stays on plain `test`.
    */
  private def liveTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  liveTest("stream_event text_delta becomes AssistantTextDelta"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-1"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    // The delta opens a message; `result:success` settles it, and the base-class
    // auto-close injects the owed AssistantMessageEnd.
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("hello"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest("result message finishes the turn and carries usage"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-2","result":"done","usage":{"input_tokens":5,"output_tokens":7}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val Right(result) = live.awaitResult(): @unchecked
    assertEquals(result.output, "done")
    assertEquals(result.usage, usage(5L, 7L))

  // Nothing on the wire reports how many requests a turn made. The CLI splits
  // one model response into several `assistant` messages sharing one id, and a
  // response can carry several tool calls, so neither message count nor tool
  // count is the answer — only the distinct ids are.
  liveTest("a turn's API-call count is its distinct response ids"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

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
    process.closeStderr()

    val _ = live.events.toList
    val Right(result) = live.awaitResult(): @unchecked
    assertEquals(result.usage.apiCalls, Some(2L))

  liveTest(
    "is_error after streaming deltas emits a short marker, not a duplicate"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"API Error: 400 quota exceeded"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"sid-x","result":"API Error: 400 quota exceeded","is_error":true}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    val errors = events.collect { case TurnEvent.Error(msg) => msg }
    assertEquals(
      errors.size,
      1,
      s"expected exactly one Error event; got: $errors"
    )
    assert(
      !errors.head.contains("400 quota exceeded"),
      s"the error event should not duplicate the streamed body; got: ${errors.head}"
    )
    // The delta opened a turn; the out-of-band is_error settles it, and the
    // reader injects the owed AssistantMessageEnd — so the
    // settled-failure sequence is grammar-clean.
    assertEquals(events.last, TurnEvent.AssistantMessageEnd)
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val failure = intercept[OrcaFlowException](live.awaitResult())
    assert(
      failure.getMessage.contains("400 quota exceeded"),
      s"awaitResult should still carry the full body; got: ${failure.getMessage}"
    )

  liveTest(
    "result message with is_error=true fails the session and surfaces the message"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"sid-err","result":"API Error: 400 rate limited","is_error":true}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(msg) => msg.contains("rate limited")
        case _                    => false
      },
      s"expected an Error event carrying the result body; got: $events"
    )
    // Out-of-band is_error before any assistant message: no message opened, so the
    // settled-failure sequence is grammar-clean with no AssistantMessageEnd.
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val failure = intercept[OrcaFlowException](live.awaitResult())
    assert(failure.getMessage.contains("rate limited"))

  // The empty-body case is where the CLI puts the whole reason in `subtype`
  // (a resume replaying a queued pseudo-turn, an exhausted turn budget, …), so
  // it must stand in for the message rather than leaving a bare marker.
  liveTest("is_error with an empty body names the result subtype"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"result","subtype":"error_max_turns","session_id":"sid-empty","is_error":true}"""
    )
    process.closeStdout()
    process.closeStderr()

    val errors = live.events.toList.collect { case TurnEvent.Error(msg) =>
      msg
    }
    assertEquals(
      errors,
      List("claude reported is_error (subtype error_max_turns)")
    )
    val failure = intercept[OrcaFlowException](live.awaitResult())
    assertEquals(
      failure.getMessage,
      "claude turn failed (subtype error_max_turns, session sid-empty): " +
        "claude reported is_error (subtype error_max_turns)"
    )

  // The failing turn's tokens are on the wire in the same `result` frame; the
  // exception is the only way they can still reach the cost summary.
  liveTest("is_error carries the result's debit on the thrown failure"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"result","subtype":"error_during_execution","session_id":"sid-cost","is_error":true,"usage":{"input_tokens":11,"output_tokens":3,"cache_read_input_tokens":7},"total_cost_usd":0.25,"model":"claude-sonnet-4-6"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val failure = intercept[AgentTurnFailed](live.awaitResult())
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

  // `Observed(Usage.empty)` here would reach the cost summary as a measured
  // zero.
  liveTest("is_error without a usage object reports an unobserved debit"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"sid-nousage","result":"API Error: 400 quota exceeded","is_error":true}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val failure = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(failure.debit, TurnDebit.Unobserved)

  liveTest(
    "is_error after a tool-only message (no assistant text) surfaces the full error body"
  ):
    // The deltasSinceLastFullMessage-vs-open-message distinction (see that
    // field's scaladoc): the ToolResult below opens a message, but no text/
    // thinking delta ever streamed, so is_error must NOT collapse into the "see
    // message above" marker — there IS no message above.
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

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
    process.closeStderr()

    val events = live.events.toList
    val errors = events.collect { case TurnEvent.Error(msg) => msg }
    assertEquals(
      errors.size,
      1,
      s"expected exactly one Error event; got: $errors"
    )
    assert(
      errors.head.contains("500 internal error"),
      s"expected the full error body, not the marker; got: ${errors.head}"
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val failure = intercept[OrcaFlowException](live.awaitResult())
    assert(failure.getMessage.contains("500 internal error"))

  liveTest(
    "cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    live.cancel()
    live.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected Left(OrcaInteractiveCancelled), got: $other")
    assertEquals(process.sigIntCount, 1)

  liveTest("control_request surfaces as an Error without writing to stdin"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"control_request","request_id":"req-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"cmd":"ls"}}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-3"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val errors =
      live.events.toList.collect { case TurnEvent.Error(m) => m }
    assert(
      errors.sizeIs == 1 && errors.head.contains(
        "control_request (can_use_tool)"
      ),
      errors
    )
    val _ = live.awaitResult()
    assertEquals(process.writes, Nil)

  liveTest(
    "assistant message with text falls back to an AssistantTextDelta when no partials streamed"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"no-partials"}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-fallback"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("no-partials"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest("a failed permission-refusal tool_result emits ToolDenied"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"id-1","content":"Claude requested permissions to use mcp__visdom__agents_md, but you haven't granted it yet.","is_error":true}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-td"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.ToolDenied("mcp__visdom__agents_md"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest(
    "a successful tool_result quoting the refusal phrase stays a ToolResult"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)
    val quoted =
      "Claude requested permissions to use Bash, but you haven't granted it yet."

    process.enqueueStdout(
      s"""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"id-1","content":"$quoted","is_error":false}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-tq"}"""
    )
    process.closeStdout()
    process.closeStderr()

    assertEquals(
      live.events.toList.headOption,
      Some(TurnEvent.ToolResult(None, ok = true, quoted))
    )
    val _ = live.awaitResult()

  liveTest("user message with tool_result blocks emits ToolResult events"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"id-1","content":"output","is_error":false}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-tr"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    // The ToolResult opens a message (a tool ran); `result:success` settles it and
    // the base-class auto-close injects the owed AssistantMessageEnd.
    assertEquals(
      events,
      List(
        TurnEvent.ToolResult(
          toolName = None,
          ok = true,
          content = "output"
        ),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest(
    "malformed NDJSON line surfaces as TurnEvent.Error and the loop continues"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout("this is not json")
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-malformed"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(msg) => msg.contains("Failed to parse")
        case _                    => false
      },
      s"expected a parse-error event; got: $events"
    )
    val _ = live.awaitResult()

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
      val live =
        ClaudeTurn(process, askUser = AskUserChannel.Mcp(askUser))
      val bridge = askUser.bridge
      assert(live.canAskUser, "canAskUser must be true when a bridge is wired")

      // From a separate fork, ask the bridge — simulates the MCP handler.
      val askResult = forkUser:
        bridge.ask("What's your favourite colour?")

      // The turn's drainer thread should surface the question on
      // events; respond closure unblocks the ask fork.
      val firstEvent = live.events.next()
      val (question, respond) = firstEvent match
        case TurnEvent.UserQuestion(q, r) => (q, r)
        case other => fail(s"expected UserQuestion; got: $other")
      assertEquals(question, "What's your favourite colour?")
      respond("magenta")
      assertEquals(askResult.join(), "magenta")

      // Wind the session down so the test exits cleanly.
      process.enqueueStdout(
        """{"type":"result","subtype":"success","session_id":"sid-q"}"""
      )
      process.closeStdout()
      process.closeStderr()
      val _ = live.events.toList
      val _ = live.awaitResult()

  liveTest("canAskUser is false when no bridge is provided"):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)
    assertEquals(live.canAskUser, false)
    process.closeStdout()
    process.closeStderr()
    val _ = live.events.toList

  liveTest(
    "assistantMessage suppresses the agent's ToolUse for ask_user"
  ):
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    // Assistant message carrying a tool_use block for the MCP-prefixed ask_user
    // tool name. Renderer-side suppression drops the AssistantToolCall event,
    // so this message bears no assistant activity — the trailing
    // AssistantMessageEnd closes an empty message and the base funnel drops it.
    process.enqueueStdout(
      s"""{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"tu_1","name":"${ClaudeBackend.AskUserToolName}","input":{"question":"x"}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-tu"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      !events.exists(_.isInstanceOf[TurnEvent.AssistantToolCall]),
      s"ask_user ToolCall should have been suppressed; got: $events"
    )
    assert(
      !events.contains(TurnEvent.AssistantMessageEnd),
      s"a suppressed ask_user-only message is empty; its message end must be dropped, got: $events"
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest(
    "structured mode suppresses the CLI-injected StructuredOutput ToolUse and its tool_result echo"
  ):
    val process = new FakePipedCliProcess()
    val live =
      ClaudeTurn(process, outputSchema = Some("{}"))

    // The final message of a `--json-schema` call: the model "exits" by calling
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
    process.closeStderr()

    val events = live.events.toList
    assert(
      !events.exists(_.isInstanceOf[TurnEvent.AssistantToolCall]),
      s"StructuredOutput ToolCall should have been suppressed; got: $events"
    )
    assert(
      !events.exists(_.isInstanceOf[TurnEvent.ToolResult]),
      s"StructuredOutput's tool_result echo should have been dropped; got: $events"
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest(
    "a tool named StructuredOutput is NOT suppressed outside structured mode"
  ):
    // The suppression is gated on `outputSchema.isDefined`: in a plain run the
    // name can only be a genuine (if unluckily named) user tool, and hiding it
    // would silently drop real activity.
    val process = new FakePipedCliProcess()
    val live = ClaudeTurn(process)

    process.enqueueStdout(
      s"""{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"tu_so2","name":"${ClaudeBackend.StructuredOutputToolName}","input":{}}]}}"""
    )
    process.enqueueStdout(
      """{"type":"result","subtype":"success","session_id":"sid-so2"}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.AssistantToolCall(name, _) =>
          name == ClaudeBackend.StructuredOutputToolName
        case _ => false
      },
      s"an unlucky user tool named StructuredOutput must stay visible in a plain run; got: $events"
    )
    val _ = live.awaitResult()
