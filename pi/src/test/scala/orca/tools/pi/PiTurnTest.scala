package orca.tools.pi

import orca.backend.{AskUserChannel, TurnEvent, TurnEventConformance}
import orca.events.{TurnDebit, Usage}
import orca.agents.{BackendTag, Model, SessionId, WireSessionId, onWire}
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}
import orca.subprocess.FakePipedCliProcess
import orca.testkit.Usages.usage
import ox.{Ox, supervised}

class PiTurnTest extends munit.FunSuite:

  private val sid: SessionId[BackendTag.Pi.type] =
    SessionId[BackendTag.Pi.type]("pi-session")

  /** `PiTurn` forks its reader/stderr workers into the caller's per-turn Ox, so
    * construction needs a `using Ox`. Run each test body in a fresh supervised
    * scope that provides it.
    */
  private def liveTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  liveTest(
    "text deltas complete with AssistantMessageEnd and produce AgentResult"
  ):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hello"}}"""
    )
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"hello"}],"model":"anthropic/claude-sonnet","usage":{"input":10,"output":3,"cacheRead":1,"cacheWrite":2,"cost":{"total":0.01}}}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("hello"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val Right(result) = live.awaitResult(): @unchecked
    val wire: WireSessionId[BackendTag.Pi.type] = sid.onWire
    assertEquals(result.wireId, wire)
    assertEquals(result.output, "hello")
    assertEquals(result.model.map(_.name), Some("anthropic/claude-sonnet"))
    assertEquals(
      result.usage,
      Usage(
        // pi's `input` counts only the fresh prompt.
        freshInputTokens = 10L,
        cacheReadInputTokens = 1L,
        cacheWriteInputTokens = 2L,
        outputTokens = 3L,
        reasoningOutputTokens = 0L,
        cost = Some(BigDecimal("0.01")),
        apiCalls = None
      )
    )
    assertEquals(process.sigIntCount, 1)
    assert(process.isStdinClosed)

  liveTest("message_end emits assistant text when no text delta streamed"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"fallback"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    assertEquals(
      live.events.toList,
      List(
        TurnEvent.AssistantTextDelta("fallback"),
        TurnEvent.AssistantMessageEnd
      )
    )
    val Right(result) = live.awaitResult(): @unchecked
    assertEquals(result.output, "fallback")

  liveTest("thinking delta becomes AssistantThinkingDelta"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"checking"}}"""
    )
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assertEquals(
      events.head,
      TurnEvent.AssistantThinkingDelta("checking")
    )
    val _ = live.awaitResult()

  liveTest("tool execution events become tool call and tool result"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"tool_execution_start","toolCallId":"call-1","toolName":"bash","args":{"command":"ls"}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_execution_end","toolCallId":"call-1","toolName":"bash","result":{"content":[{"type":"text","text":"ok\n"}],"details":{}},"isError":false}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    events(0) match
      case TurnEvent.AssistantToolCall(name, rawInput) =>
        assertEquals(name, "bash")
        assert(rawInput.contains("ls"))
      case other => fail(s"expected AssistantToolCall, got $other")
    events(1) match
      case TurnEvent.ToolResult(name, ok, content) =>
        assertEquals(name, Some("bash"))
        assertEquals(ok, true)
        assertEquals(content, "ok\n")
      case other => fail(s"expected ToolResult, got $other")
    val _ = live.awaitResult()

  liveTest("a tool-call-only turn still ends with AssistantMessageEnd"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"tool_execution_start","toolCallId":"call-1","toolName":"bash","args":{"command":"ls"}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_execution_end","toolCallId":"call-1","toolName":"bash","result":{"content":[{"type":"text","text":"ok\n"}],"details":{}},"isError":false}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assertEquals(events.count(_ == TurnEvent.AssistantMessageEnd), 1)
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val _ = live.awaitResult()

  liveTest("unknown events are ignored"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout("""{"type":"session","id":"s"}""")
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assert(!events.exists(_.isInstanceOf[TurnEvent.Error]))
    val Right(result) = live.awaitResult(): @unchecked
    assertEquals(result.output, "ok")

  liveTest("usage accumulates across assistant messages"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"first"}],"usage":{"input":1,"output":2,"cacheRead":3}}}"""
    )
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"second"}],"usage":{"input":4,"output":5,"cacheWrite":6}}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    // Two wire message_ends, but one orca message → one AssistantMessageEnd.
    assertEquals(events.count(_ == TurnEvent.AssistantMessageEnd), 1)
    val Right(result) = live.awaitResult(): @unchecked
    assertEquals(result.output, "second")
    assertEquals(
      result.usage,
      Usage(
        // (1 fresh + 3 read) + (4 fresh + 6 write)
        freshInputTokens = 5L,
        cacheReadInputTokens = 3L,
        cacheWriteInputTokens = 6L,
        outputTokens = 7L,
        reasoningOutputTokens = 0L,
        cost = None,
        apiCalls = None
      )
    )

  liveTest("failed prompt response fails the turn"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"response","id":"orca-prompt","command":"prompt","success":false,"error":"model unavailable"}"""
    )

    val events = live.events.toList
    assert(events.exists {
      case TurnEvent.Error(message) =>
        message.contains("model unavailable")
      case _ => false
    })
    // Failure with no assistant activity: no message opened, so no message end.
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val ex = intercept[OrcaFlowException](live.awaitResult())
    assert(ex.getMessage.contains("model unavailable"))

  // A pi turn runs many assistant messages; a late RPC failure would otherwise
  // discard every message_end's usage already accrued for the turn.
  liveTest("a failed response debits the usage accrued earlier in the turn"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","model":"anthropic/claude-sonnet","content":[{"type":"text","text":"first"}],"usage":{"input":40,"output":9}}}"""
    )
    process.enqueueStdout(
      """{"type":"response","id":"orca-prompt","command":"prompt","success":false,"error":"model unavailable"}"""
    )

    val _ = live.events.toList
    val ex = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(
      ex.debit,
      TurnDebit.Observed(usage(40L, 9L), Some(Model("anthropic/claude-sonnet")))
    )

  liveTest(
    "extension UI input request becomes UserQuestion and writes response"
  ):
    val process = new FakePipedCliProcess()
    val live =
      PiTurn(process, sid, "go", askUser = AskUserChannel.Native)
    assert(live.canAskUser)

    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-1","method":"input","title":"What branch?"}"""
    )

    live.events.next() match
      case TurnEvent.UserQuestion(question, respond) =>
        assertEquals(question, "What branch?")
        respond("main")
      case other => fail(s"expected UserQuestion, got $other")

    assert(process.writes.exists(_.contains("extension_ui_response")))
    assert(process.writes.exists(_.contains("main")))
    live.cancel()
    live.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected cancellation after test cleanup, got $other")

  // Ctrl-C at an interactive prompt abandons a turn that has already been
  // billed for every assistant message it ran.
  liveTest("a cancelled turn carries the usage accrued before the cancel"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","model":"anthropic/claude-sonnet","content":[{"type":"text","text":"partial"}],"usage":{"input":60,"output":4}}}"""
    )
    assertEquals(
      live.events.next(),
      TurnEvent.AssistantTextDelta("partial")
    )
    live.cancel()
    live.awaitResult() match
      case Left(cancelled) =>
        assertEquals(
          cancelled.debit,
          TurnDebit.Observed(
            usage(60L, 4L),
            Some(Model("anthropic/claude-sonnet"))
          )
        )
      case other => fail(s"expected cancellation, got $other")

  liveTest("fire-and-forget extension UI requests are ignored"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-status","method":"setStatus","statusKey":"x","statusText":"running"}"""
    )
    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-widget","method":"setWidget","widgetKey":"x","widgetLines":["running"]}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assertEquals(events, Nil)
    assert(!process.writes.exists(_.contains("extension_ui_response")))
    val _ = live.awaitResult()

  liveTest(
    "an extension_ui_request without a method is cancelled, not dropped"
  ):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-x","title":"hm"}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val _ = live.events.toList
    // A cancel is written so Pi doesn't block waiting on a reply.
    assert(
      process.writes.exists(_.contains("extension_ui_response")),
      process.writes
    )
    val _ = live.awaitResult()

  liveTest(
    "message_end without content surfaces the error, not a parse failure"
  ):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","errorMessage":"model exploded"}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assert(
      events.exists {
        case TurnEvent.Error(m) => m.contains("model exploded")
        case _                  => false
      },
      events
    )
    assert(
      !events.exists {
        case TurnEvent.Error(m) => m.contains("parse")
        case _                  => false
      },
      events
    )
    val _ = live.awaitResult()

  liveTest("clean exit before agent_end fails"):
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val live = PiTurn(process, sid, "go")
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val ex = intercept[OrcaFlowException](live.awaitResult())
    assert(ex.getMessage.contains("agent_end"))

  liveTest("stderr diagnostics are attached to failures"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    val live = PiTurn(process, sid, "go")
    process.enqueueStderr("Pi auth failed")
    process.closeStdout()
    process.closeStderr()

    val _ = live.events.toList
    val ex = intercept[OrcaFlowException](live.awaitResult())
    assert(ex.getMessage.contains("Pi auth failed"), ex.getMessage)

  liveTest("terminal notification stderr noise is ignored"):
    val process = new FakePipedCliProcess()
    val live = PiTurn(process, sid, "go")

    process.enqueueStderr(
      "]777;notify;π;Implemented. Changed: extensions/relay/core/file.ts"
    )
    process.closeStderr()
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = live.events.toList
    assertEquals(events, Nil)
    val _ = live.awaitResult()

  liveTest("stderr strips terminal controls before surfacing diagnostics"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    val live = PiTurn(process, sid, "go")

    process.enqueueStderr("auth\u001b[?25l failed\u001b[2K now")
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assert(events.exists {
      case TurnEvent.Error(message) =>
        message.contains("auth failed now") && !message.contains("?25l")
      case _ => false
    })
    val ex = intercept[OrcaFlowException](live.awaitResult())
    assert(ex.getMessage.contains("auth failed now"), ex.getMessage)
