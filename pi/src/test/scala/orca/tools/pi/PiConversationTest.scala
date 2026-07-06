package orca.tools.pi

import orca.backend.{ConversationEvent, ConversationEventConformance}
import orca.events.Usage
import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}
import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class PiConversationTest extends munit.FunSuite:

  private val sid: SessionId[BackendTag.Pi.type] =
    SessionId[BackendTag.Pi.type]("pi-session")

  /** `PiConversation` forks its reader/stderr workers into the caller's
    * per-turn Ox, so construction needs a `using Ox`. Run each test body in a
    * fresh supervised scope that provides it.
    */
  private def convTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  convTest(
    "text deltas complete with AssistantTurnEnd and produce AgentResult"
  ):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hello"}}"""
    )
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"hello"}],"model":"anthropic/claude-sonnet","usage":{"input":10,"output":3,"cacheRead":1,"cacheWrite":2,"cost":{"total":0.01}}}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("hello"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val Right(result) = conv.awaitResult(): @unchecked
    val wire: WireSessionId[BackendTag.Pi.type] = sid.onWire
    assertEquals(result.wireId, wire)
    assertEquals(result.output, "hello")
    assertEquals(result.model.map(_.name), Some("anthropic/claude-sonnet"))
    assertEquals(result.usage, Usage(10L, 3L, Some(BigDecimal("0.01")), 3L))
    assertEquals(process.sigIntCount, 1)
    assert(process.isStdinClosed)

  convTest("message_end emits assistant text when no text delta streamed"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"fallback"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    assertEquals(
      conv.events.toList,
      List(
        ConversationEvent.AssistantTextDelta("fallback"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "fallback")

  convTest("thinking delta becomes AssistantThinkingDelta"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"checking"}}"""
    )
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assertEquals(
      events.head,
      ConversationEvent.AssistantThinkingDelta("checking")
    )
    val _ = conv.awaitResult()

  convTest("tool execution events become tool call and tool result"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"tool_execution_start","toolCallId":"call-1","toolName":"bash","args":{"command":"ls"}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_execution_end","toolCallId":"call-1","toolName":"bash","result":{"content":[{"type":"text","text":"ok\n"}],"details":{}},"isError":false}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    events(0) match
      case ConversationEvent.AssistantToolCall(name, rawInput) =>
        assertEquals(name, "bash")
        assert(rawInput.contains("ls"))
      case other => fail(s"expected AssistantToolCall, got $other")
    events(1) match
      case ConversationEvent.ToolResult(name, ok, content) =>
        assertEquals(name, Some("bash"))
        assertEquals(ok, true)
        assertEquals(content, "ok\n")
      case other => fail(s"expected ToolResult, got $other")
    val _ = conv.awaitResult()

  convTest("a tool-call-only turn still ends with AssistantTurnEnd"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"tool_execution_start","toolCallId":"call-1","toolName":"bash","args":{"command":"ls"}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_execution_end","toolCallId":"call-1","toolName":"bash","result":{"content":[{"type":"text","text":"ok\n"}],"details":{}},"isError":false}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assertEquals(events.count(_ == ConversationEvent.AssistantTurnEnd), 1)
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest("unknown events are ignored"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout("""{"type":"session","id":"s"}""")
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assert(!events.exists(_.isInstanceOf[ConversationEvent.Error]))
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "ok")

  convTest("usage accumulates across assistant messages"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"first"}],"usage":{"input":1,"output":2,"cacheRead":3}}}"""
    )
    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"second"}],"usage":{"input":4,"output":5,"cacheWrite":6}}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    // Two assistant message_ends, but one turn → exactly one AssistantTurnEnd.
    assertEquals(events.count(_ == ConversationEvent.AssistantTurnEnd), 1)
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "second")
    assertEquals(result.usage, Usage(5L, 7L, None, 9L))

  convTest("failed prompt response fails the conversation"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"response","id":"orca-prompt","command":"prompt","success":false,"error":"model unavailable"}"""
    )

    val events = conv.events.toList
    assert(events.exists {
      case ConversationEvent.Error(message) =>
        message.contains("model unavailable")
      case _ => false
    })
    // Failure with no assistant activity: no turn opened, so no turn end.
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val ex = intercept[OrcaFlowException](conv.awaitResult())
    assert(ex.getMessage.contains("model unavailable"))

  convTest(
    "extension UI input request becomes UserQuestion and writes response"
  ):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid, askUserEnabled = true)
    assert(conv.canAskUser)

    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-1","method":"input","title":"What branch?"}"""
    )

    conv.events.next() match
      case ConversationEvent.UserQuestion(question, respond) =>
        assertEquals(question, "What branch?")
        respond("main")
      case other => fail(s"expected UserQuestion, got $other")

    assert(process.writes.exists(_.contains("extension_ui_response")))
    assert(process.writes.exists(_.contains("main")))
    conv.cancel()
    conv.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected cancellation after test cleanup, got $other")

  convTest("fire-and-forget extension UI requests are ignored"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-status","method":"setStatus","statusKey":"x","statusText":"running"}"""
    )
    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-widget","method":"setWidget","widgetKey":"x","widgetLines":["running"]}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assertEquals(events, Nil)
    assert(!process.writes.exists(_.contains("extension_ui_response")))
    val _ = conv.awaitResult()

  convTest(
    "an extension_ui_request without a method is cancelled, not dropped"
  ):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"extension_ui_request","id":"ui-x","title":"hm"}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val _ = conv.events.toList
    // A cancel is written so Pi doesn't block waiting on a reply.
    assert(
      process.writes.exists(_.contains("extension_ui_response")),
      process.writes
    )
    val _ = conv.awaitResult()

  convTest(
    "message_end without content surfaces the error, not a parse failure"
  ):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","errorMessage":"model exploded"}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(m) => m.contains("model exploded")
        case _                          => false
      },
      events
    )
    assert(
      !events.exists {
        case ConversationEvent.Error(m) => m.contains("parse")
        case _                          => false
      },
      events
    )
    val _ = conv.awaitResult()

  convTest("clean exit before agent_end fails"):
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val conv = new PiConversation(process, sid)
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val ex = intercept[OrcaFlowException](conv.awaitResult())
    assert(ex.getMessage.contains("agent_end"))

  convTest("stderr diagnostics are attached to failures"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    val conv = new PiConversation(process, sid)
    process.enqueueStderr("Pi auth failed")
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val ex = intercept[OrcaFlowException](conv.awaitResult())
    assert(ex.getMessage.contains("Pi auth failed"), ex.getMessage)

  convTest("terminal notification stderr noise is ignored"):
    val process = new FakePipedCliProcess()
    val conv = new PiConversation(process, sid)

    process.enqueueStderr(
      "]777;notify;π;Implemented. Changed: extensions/relay/core/file.ts"
    )
    process.closeStderr()
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")

    val events = conv.events.toList
    assertEquals(events, Nil)
    val _ = conv.awaitResult()

  convTest("stderr strips terminal controls before surfacing diagnostics"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    val conv = new PiConversation(process, sid)

    process.enqueueStderr("auth\u001b[?25l failed\u001b[2K now")
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(events.exists {
      case ConversationEvent.Error(message) =>
        message.contains("auth failed now") && !message.contains("?25l")
      case _ => false
    })
    val ex = intercept[OrcaFlowException](conv.awaitResult())
    assert(ex.getMessage.contains("auth failed now"), ex.getMessage)
