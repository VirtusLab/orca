package orca.tools.opencode

import orca.AgentTurnFailed
import orca.backend.{
  ApprovalDecision,
  ConversationEvent,
  ConversationEventConformance,
  StreamSource
}
import ox.{Ox, supervised}

class OpencodeConversationTest extends munit.FunSuite:

  /** `OpencodeConversation` forks its reader into the caller's per-turn Ox, so
    * construction needs a `using Ox`. Run each test body in a fresh supervised
    * scope that provides it — keeping build + consume in one scope so the
    * reader fork isn't cancelled before the events are drained.
    */
  private def convTest(name: String)(body: Ox ?=> Any): Unit =
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

  private def data(json: String): String = s"data: $json"

  private def conversation(
      lines: List[String],
      session: String = "ses_A",
      schema: Option[String] = None
  ): (OpencodeConversation, RecordingHttp) =
    val http = new RecordingHttp
    val conv = new OpencodeConversation(
      source(lines),
      http,
      session,
      outputSchema = schema,
      canAsk = true
    )
    (conv, http)

  convTest(
    "free-form turn: text deltas, then result from accrued text + tokens"
  ):
    val (conv, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"Hel"}}"""
        ),
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"lo"}}"""
        ),
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","tokens":{"input":10,"output":2,"reasoning":0,"cache":{"read":1,"write":0}},"modelID":"gpt-4o-mini","finish":"stop"}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("Hel"),
        ConversationEvent.AssistantTextDelta("lo"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val result = conv.awaitResult().toOption.get
    assertEquals(result.output, "Hello")
    assertEquals(
      result.usage.inputTokens,
      11L
    ) // input + cache.read + cache.write
    assertEquals(result.usage.cachedInputTokens, 1L)
    assertEquals(result.usage.outputTokens, 2L)
    assertEquals(result.model.map(_.name), Some("gpt-4o-mini"))

  convTest("structured turn: result is the validated object, not text"):
    val (conv, _) = conversation(
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
    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.ToolResult(Some("StructuredOutput"), ok = true, "ok"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    assertEquals(conv.awaitResult().toOption.get.output, """{"x":1}""")

  convTest("a repeated tool part surfaces one AssistantToolCall"):
    val running =
      data(
        """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","state":{"status":"running","input":{"command":"echo hi"}},"id":"prt_1","sessionID":"ses_A"}}}"""
      )
    val (conv, _) = conversation(
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
      conv.events.toList,
      List(
        ConversationEvent
          .AssistantToolCall("bash", """{"command":"echo hi"}"""),
        ConversationEvent.ToolResult(Some("bash"), ok = true, "hi\n"),
        ConversationEvent.AssistantTurnEnd
      )
    )

  convTest("events for other sessions are dropped"):
    val (conv, _) = conversation(
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
      conv.events.toList,
      List(
        ConversationEvent.AssistantTextDelta("hi"),
        ConversationEvent.AssistantTurnEnd
      )
    )

  convTest("blank, comment, and event: framing lines are skipped"):
    val (conv, _) = conversation(
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
      conv.events.toList,
      List(
        ConversationEvent.AssistantTextDelta("x"),
        ConversationEvent.AssistantTurnEnd
      )
    )

  convTest("free-form turn with no message.updated: text result, zero usage"):
    val (conv, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"hi"}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    conv.events.foreach(_ => ())
    val result = conv.awaitResult().toOption.get
    assertEquals(result.output, "hi")
    assertEquals(result.usage.inputTokens, 0L)
    assertEquals(result.usage.outputTokens, 0L)
    assertEquals(result.model, None)

  convTest("idle with no assistant message at all fails the turn"):
    val (conv, _) = conversation(
      List(
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    conv.events.foreach(_ => ())
    intercept[AgentTurnFailed](conv.awaitResult())

  convTest("message.updated carrying info.error fails the turn"):
    val (conv, _) = conversation(
      List(
        data(
          """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","error":{"message":"model exploded"}}}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    conv.events.foreach(_ => ())
    intercept[AgentTurnFailed](conv.awaitResult())

  convTest(
    "a failure settle after assistant activity still emits AssistantTurnEnd"
  ):
    val (conv, _) = conversation(
      List(
        data(
          """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"text","delta":"partial"}}"""
        ),
        data(
          """{"type":"session.error","properties":{"sessionID":"ses_A","error":{"message":"boom"}}}"""
        )
      )
    )
    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("partial"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    intercept[AgentTurnFailed](conv.awaitResult())

  convTest("session.error fails the turn"):
    val (conv, _) = conversation(
      List(
        data(
          """{"type":"session.error","properties":{"sessionID":"ses_A","error":{"message":"boom"}}}"""
        )
      )
    )
    val events = conv.events.toList
    // No activity before the error, so the failure settle emits no turn end —
    // an empty turn is forbidden by the grammar.
    assert(!events.contains(ConversationEvent.AssistantTurnEnd), events)
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    intercept[AgentTurnFailed](conv.awaitResult())

  convTest("answering a question.asked POSTs the reply"):
    val (conv, http) = conversation(
      List(
        data(
          """{"type":"question.asked","properties":{"id":"que_1","sessionID":"ses_A","questions":[{"question":"Color?","options":[{"label":"Blue","description":""}]}]}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    conv.events.foreach:
      case ConversationEvent.UserQuestion(q, respond) =>
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
    val (conv, http) = conversation(
      List(
        data(
          """{"type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_A","permission":"bash","patterns":["echo hi"]}}"""
        ),
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    conv.events.foreach:
      case ConversationEvent.ApproveTool(tool, input, respond) =>
        assertEquals(tool, "bash")
        assertEquals(input, "echo hi")
        respond(decision)
      case _ => ()
    http.posts

  convTest("approving a permission.asked POSTs reply=once"):
    assertEquals(
      permissionReplyPost(ApprovalDecision.Allow()),
      List("/permission/per_1/reply" -> """{"reply":"once"}""")
    )

  convTest("denying a permission.asked POSTs reply=reject"):
    assertEquals(
      permissionReplyPost(ApprovalDecision.Deny()),
      List("/permission/per_1/reply" -> """{"reply":"reject"}""")
    )

  convTest("canAskUser reflects the constructor flag"):
    val http = new RecordingHttp
    val conv =
      new OpencodeConversation(empty, http, "ses_A", None, canAsk = false)
    assertEquals(conv.canAskUser, false)

  convTest(
    "a genuine cancel before any settle POSTs /abort once; a repeat cancel() does not re-post (Epic 8.2)"
  ):
    val (conv, http) = conversation(
      List(
        data("""{"type":"session.idle","properties":{"sessionID":"ses_A"}}""")
      )
    )
    // Cancelled before the reader ever touches the (unconsumed) stream above —
    // the turn genuinely never settled, mirroring how every caller's `finally
    // cancel()` can race an interactive interrupt mid-turn.
    conv.cancel()
    conv.cancel()
    assertEquals(http.posts, List("/session/ses_A/abort" -> "{}"))
