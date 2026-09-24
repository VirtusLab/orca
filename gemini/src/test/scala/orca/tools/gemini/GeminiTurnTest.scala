package orca.tools.gemini

import orca.agents.{Model, WireSessionId}
import orca.events.TurnDebit
import orca.testkit.Usages.usage
import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.backend.{
  AskUserChannel,
  ChannelEvent,
  TurnEvent,
  TurnEventConformance
}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class GeminiTurnTest extends munit.FunSuite:

  /** `GeminiTurn` forks its reader/stderr/ask-user workers into the caller's
    * per-turn Ox, so construction needs a `using Ox`. Run each test body in a
    * fresh supervised scope that provides it. Tests managing their own scope
    * (the ask-user ones) stay on plain `test`.
    */
  private def liveTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  /** Minimal terminating tail: a `result` event ends the turn and lets
    * `awaitResult` succeed.
    */
  private def result(input: Long = 0L, output: Long = 0L): String =
    s"""{"type":"result","status":"success","stats":{"input_tokens":$input,"output_tokens":$output}}"""

  liveTest(
    "assistant message accumulates into output; init sets session + model"
  ):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout(
      """{"type":"init","session_id":"sess-1","model":"gemini-2.5-pro"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"hello"}"""
    )
    process.enqueueStdout(result(input = 10L, output = 3L))
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("hello"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(WireSessionId.value(r.wireId), "sess-1")
    assertEquals(r.output, "hello")
    assertEquals(r.usage, usage(10L, 3L))
    assertEquals(r.model.map(_.name), Some("gemini-2.5-pro"))

  liveTest("a user-role message is ignored (prompt echo, not agent output)"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"user","content":"List files"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"model","content":"done"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      !events.contains(TurnEvent.AssistantTextDelta("List files")),
      s"user message must not become agent output; got: $events"
    )
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(r.output, "done")

  liveTest("multiple assistant chunks concatenate into the output"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"foo"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"bar"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(r.output, "foobar")

  liveTest(
    "the opening prompt becomes a UserMessage event before agent output"
  ):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process, openingPrompt = Some("do the thing"))

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assertEquals(events.head, TurnEvent.UserMessage("do the thing"))
    val _ = live.awaitResult()

  liveTest("tool_use + tool_result become AssistantToolCall + ToolResult"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b1","parameters":{"command":"ls"}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b1","status":"success","output":"hello.txt"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"done"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.contains(
        TurnEvent.AssistantToolCall("Bash", """{"command":"ls"}""")
      ),
      s"expected AssistantToolCall for Bash; got: $events"
    )
    assert(
      events.contains(
        TurnEvent.ToolResult(Some("Bash"), ok = true, "hello.txt")
      ),
      s"expected matching ToolResult keyed by name; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("a tool whose name merely contains 'ask_user' is NOT suppressed"):
    // Suppression matches gemini's exact MCP qualification (orca__ask_user),
    // not any name containing the slug — an unrelated tool must still surface.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"ask_user_for_help","tool_id":"x1","parameters":{}}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.AssistantToolCall("ask_user_for_help", _) => true
        case _                                                   => false
      },
      s"a non-MCP tool must not be suppressed; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("tool_result with a non-success status yields ok=false"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b1","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b1","status":"error","output":"boom"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    val tr = events
      .collectFirst { case r: TurnEvent.ToolResult => r }
      .getOrElse(fail("expected a ToolResult"))
    assertEquals(tr.ok, false)
    val _ = live.awaitResult()

  liveTest("benign gemini stderr chatter is filtered (no Error events)"):
    // Observed on every successful headless run (gemini 0.45.2): a 256-color
    // warning, YOLO-mode notices, a cwd-reset line, and IDE-companion probe
    // chatter — all informational.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStderr(
      "Warning: 256-color support not detected. Using a terminal with at least 256-color support is recommended for a better visual experience."
    )
    process.enqueueStderr(
      "YOLO mode is enabled. All tool calls will be automatically approved."
    )
    process.enqueueStderr("Shell cwd was reset to /some/dir")
    process.enqueueStderr(
      "[ERROR] [IDEClient] Failed to connect to IDE companion extension. Please ensure the extension is running."
    )
    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      !events.exists {
        case TurnEvent.Error(_) => true
        case _                  => false
      },
      s"benign stderr must not surface as Error events; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("a real stderr line still surfaces as TurnEvent.Error"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStderr("Error: quota exceeded for project")
    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(m) => m.contains("quota exceeded")
        case _                  => false
      },
      s"a real error must surface; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("stderr strips terminal controls before surfacing as an Error"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStderr("Error: quota[?25l exceeded[2K now")
    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(m) =>
          m.contains("quota exceeded now") && !m.contains("?25l")
        case _ => false
      },
      s"expected an ANSI-stripped Error; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("error event surfaces as TurnEvent.Error"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("""{"type":"error","message":"rate limited"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(m) => m.contains("rate limited")
        case _                  => false
      },
      s"expected an Error event; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("malformed JSONL surfaces as Error and the loop continues"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("not json at all")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(m) => m.contains("Failed to parse")
        case _                  => false
      },
      s"expected a parse-error event; got: $events"
    )
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(r.output, "ok")

  liveTest("clean exit without a result event surfaces as OrcaFlowException"):
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val ex = intercept[OrcaFlowException](live.awaitResult())
    assert(
      ex.getMessage.contains("result"),
      s"expected the missing-result message; got: ${ex.getMessage}"
    )

  liveTest(
    "missing session_id on init surfaces a visible Error and the turn fails loudly"
  ):
    // session_id is identity-critical: a missing key makes InitWire parsing
    // throw JsonReaderException, which DecodedTurn's per-line catch turns
    // into a visible Error event. With no line settling the turn, it then fails
    // loudly via the clean-exit-without-result path.
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","model":"gemini-2.5-pro"}""")
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(m) => m.contains("Failed to parse")
        case _                  => false
      },
      s"expected a parse-error Error event near the cause; got: $events"
    )
    val ex = intercept[orca.AgentTurnFailed](live.awaitResult())
    assert(
      ex.getMessage.contains("result"),
      s"expected the missing-result message; got: ${ex.getMessage}"
    )

  liveTest(
    "a message with a missing role is dropped, never treated as assistant prose"
  ):
    // A missing role is typed Role.Unknown and dropped, never landing in the
    // answer as agent output.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("""{"type":"message","content":"stray content"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      !events.contains(TurnEvent.AssistantTextDelta("stray content")),
      s"a message with an unknown role must not become agent output; got: $events"
    )
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(r.output, "")

  liveTest("a result event with a non-success status fails the turn"):
    // gemini's `result` carries a status; a failed turn that still exits 0
    // must not be reported as success. "success" is the documented good
    // token (headless stream-json) — anything else non-empty is a failure.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"partial"}"""
    )
    process.enqueueStdout(
      """{"type":"result","status":"error","stats":{"input_tokens":1,"output_tokens":1}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    // The streamed message opened a turn; the reader closes it when the
    // failing `result` settles, so even a failed turn is
    // grammar-terminated (see the contract) — completedNormally.
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val ex = intercept[orca.AgentTurnFailed](live.awaitResult())
    assert(
      ex.getMessage.contains("error"),
      s"expected the failing status in the message; got: ${ex.getMessage}"
    )

  liveTest("a successful result with no init event fails the turn"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout(result(input = 5L, output = 2L))
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val ex = intercept[orca.AgentTurnFailed](live.awaitResult())
    assertEquals(ex.debit, TurnDebit.Observed(usage(5L, 2L), None))

  // gemini's failing `result` frame carries the turn's stats, and the estimate
  // from them is the only cost signal gemini ever gives.
  liveTest("a failed result frame carries its stats as the turn's debit"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout(
      """{"type":"init","session_id":"s","model":"gemini-2.5-pro"}"""
    )
    process.enqueueStdout(
      """{"type":"result","status":"error","stats":{"input_tokens":90,"output_tokens":7}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val ex = intercept[orca.AgentTurnFailed](live.awaitResult())
    assertEquals(
      ex.debit,
      TurnDebit.Observed(usage(90L, 7L), Some(Model("gemini-2.5-pro")))
    )

  liveTest(
    "a result event with no status fails the turn, not silently success"
  ):
    // BB1: gemini's `status` is a required success|error field on the real
    // wire (verified against the CLI source, ADR 0015) — a missing value
    // can't happen in practice, but this pins the defensive fallback: treat
    // it as a failure, matching tool_result's handling below, rather than
    // silently reporting success.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"done"}"""
    )
    process.enqueueStdout(
      """{"type":"result","stats":{"input_tokens":1,"output_tokens":1}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val ex = intercept[orca.AgentTurnFailed](live.awaitResult())
    assert(
      ex.getMessage.contains("missing status"),
      s"expected the missing-status message; got: ${ex.getMessage}"
    )

  liveTest("a tool_result with no status yields ok=false, same as result"):
    // The other half of BB1's pinned case: a missing tool_result status is
    // also treated as failure, via the same ToolStatus decode.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b1","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b1","output":"boom"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    val tr = events
      .collectFirst { case r: TurnEvent.ToolResult => r }
      .getOrElse(fail("expected a ToolResult"))
    assertEquals(tr.ok, false)
    val _ = live.awaitResult()

  liveTest("interleaved tool calls are each keyed back to their own name"):
    // Two tool calls complete out of order (B before A); each tool_result,
    // which carries only the id, must resolve to the right tool_name.
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Read","tool_id":"a","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b","status":"success","output":"ls-out"}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"a","status":"success","output":"file-out"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      events.contains(
        TurnEvent.ToolResult(Some("Bash"), ok = true, "ls-out")
      ),
      s"tool_result b must key to Bash; got: $events"
    )
    assert(
      events.contains(
        TurnEvent.ToolResult(Some("Read"), ok = true, "file-out")
      ),
      s"tool_result a must key to Read; got: $events"
    )
    val _ = live.awaitResult()

  liveTest(
    "cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"
  ):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)
    live.cancel()
    live.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected Left(OrcaInteractiveCancelled), got: $other")
    assertEquals(process.sigIntCount, 1)

  liveTest("unknown top-level events are ignored without surfacing"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("""{"type":"some.future.event","data":42}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(
      !events.exists {
        case TurnEvent.Error(_) => true
        case _                  => false
      },
      s"unknown events must drop silently; got: $events"
    )
    val _ = live.awaitResult()

  liveTest("canAskUser is false when no bridge is provided"):
    val process = new FakePipedCliProcess()
    val live = GeminiTurn(process)
    assertEquals(live.canAskUser, false)
    process.closeStdout()
    process.closeStderr()
    val _ = live.events.toList

  test("ask_user tool_use/tool_result are suppressed (no echo)"):
    import ox.supervised
    import ox.channels.BufferCapacity
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val live = GeminiTurn(
        process,
        askUser = AskUserChannel.Mcp(AskUserSession.allocate())
      )

      process.enqueueStdout("""{"type":"init","session_id":"s"}""")
      process.enqueueStdout(
        """{"type":"tool_use","tool_name":"orca__ask_user","tool_id":"au1","parameters":{"question":"q?"}}"""
      )
      process.enqueueStdout(
        """{"type":"tool_result","tool_id":"au1","status":"success","output":"a"}"""
      )
      process.enqueueStdout(
        """{"type":"message","role":"assistant","content":"hi"}"""
      )
      process.enqueueStdout(result())
      process.closeStdout()
      process.closeStderr()

      val events = live.events.toList
      assert(
        !events.exists {
          case TurnEvent.AssistantToolCall(n, _) =>
            n.contains("ask_user")
          case TurnEvent.ToolResult(n, _, _) =>
            n.exists(_.contains("ask_user"))
          case _ => false
        },
        s"ask_user exchange must be suppressed; got: $events"
      )
      val _ = live.awaitResult()

  test("askUserBridge questions surface as UserQuestion; respond unblocks"):
    import ox.{forkUser, supervised}
    import ox.channels.BufferCapacity
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val askUser = AskUserSession.allocate()
      val live =
        GeminiTurn(process, askUser = AskUserChannel.Mcp(askUser))
      val bridge = askUser.bridge
      assert(live.canAskUser, "canAskUser must be true when a bridge is wired")

      val askResult = forkUser:
        bridge.ask("What's your favourite colour?")

      val (question, respond) = live.events.next() match
        case TurnEvent.Question(ChannelEvent.UserQuestion(q, r)) => (q, r)
        case other => fail(s"expected Question; got: $other")
      assertEquals(question, "What's your favourite colour?")
      respond("magenta")
      assertEquals(askResult.join(), "magenta")
