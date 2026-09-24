package orca.tools.opencode

import orca.AgentTurnFailed
import orca.agents.{BackendTag, Model}
import orca.events.{TurnDebit, Usage}
import orca.backend.{
  ApprovalDecision,
  AskUserChannel,
  LiveTurn,
  TurnEvent,
  TurnEventConformance,
  StreamSource
}
import ox.{Ox, supervised}

class OpencodeTurnTest extends munit.FunSuite:

  /** `OpencodeTurn` forks its reader into the caller's per-turn Ox, so
    * construction needs a `using Ox`. Run each test body in a fresh supervised
    * scope that provides it — keeping build + consume in one scope so the
    * reader fork isn't cancelled before the events are drained.
    */
  private def liveTest(name: String)(body: Ox ?=> Any): Unit =
    test(name)(supervised(body))

  /** Records reply POSTs; never serves the event stream (the source is injected
    * directly).
    */
  private class RecordingHttp extends OpencodeHttp:
    var posts: List[(String, String)] = Nil
    def postJson(path: String, body: String): String =
      posts = posts :+ (path -> body); ""
    def events(): StreamSource = empty

  private def empty: StreamSource = new StreamSource:
    def lines: Iterator[String] = Iterator.empty
    def errorLines: Iterator[String] = Iterator.empty
    def interrupt(): Unit = ()
    def tryExitCode: Option[Int] = Some(0)

  private def source(rawLines: List[String]): StreamSource = new StreamSource:
    def lines: Iterator[String] = rawLines.iterator
    def errorLines: Iterator[String] = Iterator.empty
    def interrupt(): Unit = ()
    def tryExitCode: Option[Int] = Some(0)

  /** A stream with no frames that stays open until interrupted. */
  private def openUntilInterrupted: StreamSource = new StreamSource:
    private val closed = new java.util.concurrent.CountDownLatch(1)
    def lines: Iterator[String] = new Iterator[String]:
      def hasNext: Boolean =
        closed.await()
        false
      def next(): String = throw new NoSuchElementException
    def errorLines: Iterator[String] = Iterator.empty
    def interrupt(): Unit = closed.countDown()
    def tryExitCode: Option[Int] = Some(0)

  private def data(json: String): String = s"data: $json"

  private def conversation(
      lines: List[String],
      session: String = "ses_A",
      schema: Option[String] = None
  )(using Ox): (LiveTurn[BackendTag.Opencode.type], RecordingHttp) =
    val http = new RecordingHttp
    val live = OpencodeTurn(
      source(lines),
      http,
      session,
      outputSchema = schema,
      askUser = AskUserChannel.Native
    )
    (live, http)

  liveTest(
    "free-form turn: text deltas, then result from accrued text + tokens"
  ):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"Hel"}}"""
        ),
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"lo"}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","tokens":{"input":10,"output":2,"reasoning":0,"cache":{"read":1,"write":3}},"modelID":"gpt-4o-mini","finish":"stop"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("Hel"),
        TurnEvent.AssistantTextDelta("lo"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val result = live.awaitResult().toOption.get
    assertEquals(result.output, "Hello")
    assertEquals(
      result.usage.inputTokens,
      14L
    ) // input + cache.read + cache.write
    assertEquals(result.usage.cacheReadInputTokens, 1L)
    assertEquals(result.usage.cacheWriteInputTokens, 3L)
    assertEquals(result.usage.outputTokens, 2L)
    assertEquals(result.model.map(_.name), Some("gpt-4o-mini"))

  liveTest("structured turn: result is the validated object, not text"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"StructuredOutput","state":{"status":"completed","input":{"x":1},"output":"ok"},"id":"prt_1","sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","structured":{"x":1},"finish":"tool-calls"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      ),
      schema = Some("""{"type":"object"}""")
    )
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    assertEquals(live.awaitResult().toOption.get.output, """{"x":1}""")

  liveTest(
    "structured mode: the injected StructuredOutput tool is not rendered"
  ):
    // Its payload already reaches the caller as the result, so rendering the
    // call and its result would show the same JSON twice.
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"StructuredOutput","state":{"status":"running","input":{"x":1}},"id":"prt_1","sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"StructuredOutput","state":{"status":"completed","input":{"x":1},"output":"ok"},"id":"prt_1","sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","structured":{"x":1},"finish":"tool-calls"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      ),
      schema = Some("""{"type":"object"}""")
    )
    assertEquals(live.events.toList, Nil)

  liveTest("a plain turn renders a user tool named StructuredOutput"):
    // The suppression is gated on the schema, not on the name.
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"StructuredOutput","state":{"status":"running","input":{"x":1}},"id":"prt_1","sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"StructuredOutput","state":{"status":"completed","output":"ok"},"id":"prt_1","sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","finish":"tool-calls"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    assertEquals(
      live.events.toList,
      List(
        TurnEvent.AssistantToolCall("StructuredOutput", """{"x":1}"""),
        TurnEvent.ToolResult(Some("StructuredOutput"), ok = true, "ok"),
        TurnEvent.AssistantMessageEnd
      )
    )

  liveTest("deltas on an announced reasoning part are thinking, not text"):
    // opencode names a reasoning part's accruing field "text" too, so without
    // the part announcement the chain of thought would render as the
    // assistant's message and end up in the free-form result.
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.updated","properties":{"sessionID":"ses_A","part":{"type":"reasoning","id":"prt_1"}}}"""
        ),
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","partID":"prt_1","field":"text","delta":"hmm"}}"""
        ),
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","partID":"prt_2","field":"text","delta":"Hi"}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","finish":"stop"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantThinkingDelta("hmm"),
        TurnEvent.AssistantTextDelta("Hi"),
        TurnEvent.AssistantMessageEnd
      )
    )
    assertEquals(live.awaitResult().toOption.get.output, "Hi")

  liveTest("a repeated tool part surfaces one AssistantToolCall"):
    val running =
      data(
        """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","state":{"status":"running","input":{"command":"echo hi"}},"id":"prt_1","sessionID":"ses_A"}}}"""
      )
    val (live, _) = conversation(
      List(
        running,
        running,
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","state":{"status":"completed","output":"hi\n"},"id":"prt_1","sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","finish":"tool-calls"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    assertEquals(
      live.events.toList,
      List(
        TurnEvent
          .AssistantToolCall("bash", """{"command":"echo hi"}"""),
        TurnEvent.ToolResult(Some("bash"), ok = true, "hi\n"),
        TurnEvent.AssistantMessageEnd
      )
    )

  liveTest(
    "two id-less tool parts both surface (BB5: no longer collide on a coerced \"\" key)"
  ):
    // Neither part carries an `id`; under the old `getOrElse("")` coercion
    // both keyed to the same "" entry in `startedTools`, so the second
    // AssistantToolCall was wrongly suppressed as a dupe of the first.
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","state":{"status":"running","input":{"command":"echo hi"}},"sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"read","state":{"status":"running","input":{"path":"f.txt"}},"sessionID":"ses_A"}}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","finish":"tool-calls"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    val toolCalls = live.events.toList.collect {
      case c: TurnEvent.AssistantToolCall => c.toolName
    }
    assertEquals(toolCalls, List("bash", "read"))

  liveTest("events for other sessions are dropped"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_OTHER","field":"text","delta":"nope"}}"""
        ),
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"hi"}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    assertEquals(
      live.events.toList,
      List(
        TurnEvent.AssistantTextDelta("hi"),
        TurnEvent.AssistantMessageEnd
      )
    )

  liveTest("blank, comment, and event: framing lines are skipped"):
    val (live, _) = conversation(
      List(
        ":heartbeat",
        "event: message",
        "",
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"x"}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    assertEquals(
      live.events.toList,
      List(
        TurnEvent.AssistantTextDelta("x"),
        TurnEvent.AssistantMessageEnd
      )
    )

  liveTest("free-form turn with no message.updated: text result, zero usage"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"hi"}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach(_ => ())
    val result = live.awaitResult().toOption.get
    assertEquals(result.output, "hi")
    assertEquals(result.usage.inputTokens, 0L)
    assertEquals(result.usage.outputTokens, 0L)
    assertEquals(result.model, None)

  // `tokens` and `cost` are independent fields on the assistant message, so a
  // turn can report money without counts — which must still reach the summary.
  liveTest("a completed turn keeps a reported cost when tokens are absent"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","cost":0.25,"finish":"stop"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach(_ => ())
    val result = live.awaitResult().toOption.get
    assertEquals(result.usage.cost, Some(BigDecimal("0.25")))
    assertEquals(result.usage.inputTokens, 0L)

  liveTest("idle with no assistant message at all fails the turn"):
    val (live, _) = conversation(
      List(
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach(_ => ())
    intercept[AgentTurnFailed](live.awaitResult())

  liveTest("message.updated carrying info.error fails the turn"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","error":{"message":"model exploded"}}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach(_ => ())
    intercept[AgentTurnFailed](live.awaitResult())

  // An assistant message with no `tokens` measured nothing; an all-zero debit
  // would be indistinguishable from a turn measured at zero.
  liveTest("a failed turn whose message reported no tokens debits nothing"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","modelID":"gpt-4o-mini","error":{"message":"model exploded"}}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach(_ => ())
    val failure = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(failure.debit, TurnDebit.Unobserved)

  liveTest("a failed turn debits the tokens its message did report"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","modelID":"gpt-4o-mini","tokens":{"input":10,"output":2,"reasoning":0,"cache":{"read":1,"write":3}},"error":{"message":"model exploded"}}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach(_ => ())
    val failure = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(
      failure.debit,
      TurnDebit.Observed(
        Usage(
          freshInputTokens = 10L,
          cacheReadInputTokens = 1L,
          cacheWriteInputTokens = 3L,
          outputTokens = 2L,
          reasoningOutputTokens = 0L,
          cost = None,
          apiCalls = None
        ),
        Some(Model("gpt-4o-mini"))
      )
    )

  liveTest(
    "a failure settle after assistant activity still emits AssistantMessageEnd"
  ):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"partial"}}"""
        ),
        data(
          """{"type":"session.error","properties":{"sessionID":"ses_A","error":{"message":"boom"}}}"""
        )
      )
    )
    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("partial"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    intercept[AgentTurnFailed](live.awaitResult())

  liveTest("session.error fails the turn"):
    val (live, _) = conversation(
      List(
        data(
          """{"type":"session.error","properties":{"sessionID":"ses_A","error":{"message":"boom"}}}"""
        )
      )
    )
    val events = live.events.toList
    // No activity before the error, so the failure settle emits no message
    // end — an empty message is forbidden by the grammar.
    assert(!events.contains(TurnEvent.AssistantMessageEnd), events)
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    intercept[AgentTurnFailed](live.awaitResult())

  liveTest("answering a question.asked POSTs the reply"):
    val (live, http) = conversation(
      List(
        data(
          """{"type":"question.asked","properties":{"id":"que_1","sessionID":"ses_A","questions":[{"question":"Color?","options":[{"label":"Blue","description":""}]}]}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach:
      case TurnEvent.UserQuestion(q, respond) =>
        assertEquals(q, "Color?")
        respond("Blue")
      case _ => ()
    assertEquals(
      http.posts,
      List("/question/que_1/reply" -> """{"answers":[["Blue"]]}""")
    )

  private def permissionReplyPost(
      decision: ApprovalDecision
  )(using Ox): List[(String, String)] =
    val (live, http) = conversation(
      List(
        data(
          """{"type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_A","permission":"bash","patterns":["echo hi"]}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    live.events.foreach:
      case TurnEvent.ApproveTool(tool, input, respond) =>
        assertEquals(tool, "bash")
        assertEquals(input, "echo hi")
        respond(decision)
      case _ => ()
    http.posts

  liveTest("approving a permission.asked POSTs reply=once"):
    assertEquals(
      permissionReplyPost(ApprovalDecision.Allow),
      List("/permission/per_1/reply" -> """{"reply":"once"}""")
    )

  liveTest("denying a permission.asked POSTs reply=reject"):
    assertEquals(
      permissionReplyPost(ApprovalDecision.Deny),
      List("/permission/per_1/reply" -> """{"reply":"reject"}""")
    )

  liveTest("canAskUser reflects the constructor flag"):
    val http = new RecordingHttp
    val live =
      OpencodeTurn(
        empty,
        http,
        "ses_A",
        None,
        AskUserChannel.Unavailable
      )
    assertEquals(live.canAskUser, false)

  liveTest(
    "a cancel before any settle POSTs /abort once; a repeat cancel() does not re-post"
  ):
    val http = new RecordingHttp
    val live = OpencodeTurn(
      openUntilInterrupted,
      http,
      "ses_A",
      None,
      AskUserChannel.Unavailable
    )
    live.cancel()
    live.cancel()
    assertEquals(http.posts, List("/session/ses_A/abort" -> "{}"))
