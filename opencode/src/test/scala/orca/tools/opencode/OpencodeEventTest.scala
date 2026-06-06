package orca.tools.opencode

class OpencodeEventTest extends munit.FunSuite:

  // Fixtures captured from a live `opencode serve` (ADR 0014 spike).

  test("message.part.delta(text) → TextDelta"):
    val e = OpencodeEvent.parse(
      """{"id":"evt_1","type":"message.part.delta","properties":{"sessionID":"ses_A","messageID":"msg_1","partID":"prt_1","field":"text","delta":"2"}}"""
    )
    assertEquals(e, OpencodeEvent.TextDelta("ses_A", "2"))

  test("message.part.delta(reasoning) → ReasoningDelta"):
    val e = OpencodeEvent.parse(
      """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"reasoning","delta":"hmm"}}"""
    )
    assertEquals(e, OpencodeEvent.ReasoningDelta("ses_A", "hmm"))

  test("message.part.updated(tool completed) → ToolFinished"):
    val e = OpencodeEvent.parse(
      """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","callID":"call_1","state":{"status":"completed","input":{"command":"echo hi"},"output":"hi\n"},"id":"prt_9","sessionID":"ses_A","messageID":"msg_2"}}}"""
    )
    assertEquals(
      e,
      OpencodeEvent.ToolFinished("ses_A", "prt_9", "bash", ok = true, "hi\n")
    )

  test("message.part.updated(tool running) → ToolStarted with input"):
    val e = OpencodeEvent.parse(
      """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","state":{"status":"running","input":{"command":"echo hi"}},"id":"prt_9","sessionID":"ses_A"}}}"""
    )
    assertEquals(
      e,
      OpencodeEvent.ToolStarted(
        "ses_A",
        "prt_9",
        "bash",
        """{"command":"echo hi"}"""
      )
    )

  test("message.part.updated(tool error) → ToolFinished(ok=false)"):
    val e = OpencodeEvent.parse(
      """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","state":{"status":"error","output":"nope"},"id":"prt_9","sessionID":"ses_A"}}}"""
    )
    assertEquals(
      e,
      OpencodeEvent.ToolFinished("ses_A", "prt_9", "bash", ok = false, "nope")
    )

  test("message.part.updated(non-tool part) → Ignored"):
    val e = OpencodeEvent.parse(
      """{"type":"message.part.updated","properties":{"part":{"type":"text","text":"hello","sessionID":"ses_A"}}}"""
    )
    assertEquals(e, OpencodeEvent.Ignored)

  test("message.part.updated(tool with no state yet) → Ignored"):
    val e = OpencodeEvent.parse(
      """{"type":"message.part.updated","properties":{"part":{"type":"tool","tool":"bash","id":"prt_9","sessionID":"ses_A"}}}"""
    )
    assertEquals(e, OpencodeEvent.Ignored)

  test("message.part.delta(unknown field) → Ignored"):
    assertEquals(
      OpencodeEvent.parse(
        """{"type":"message.part.delta","properties":{"sessionID":"ses_A","field":"summary","delta":"x"}}"""
      ),
      OpencodeEvent.Ignored
    )

  test("message.updated carries structured payload + tokens"):
    val e = OpencodeEvent.parse(
      """{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"ses_A","structured":{"company":"Anthropic","founded":2021},"tokens":{"total":7558,"input":7535,"output":23,"reasoning":0,"cache":{"write":0,"read":0}},"finish":"tool-calls"}}}"""
    )
    e match
      case OpencodeEvent.MessageUpdated(session, info) =>
        assertEquals(session, "ses_A")
        assertEquals(info.finish, Some("tool-calls"))
        assertEquals(
          info.structured.map(_.value),
          Some("""{"company":"Anthropic","founded":2021}""")
        )
        assertEquals(info.tokens.map(_.input), Some(7535L))
        assertEquals(info.tokens.map(_.output), Some(23L))
      case other => fail(s"expected MessageUpdated, got $other")

  test("message.updated(user echo) → Ignored (not the result)"):
    assertEquals(
      OpencodeEvent.parse(
        """{"type":"message.updated","properties":{"info":{"role":"user","sessionID":"ses_A","structured":null,"tokens":null}}}"""
      ),
      OpencodeEvent.Ignored
    )

  test("session.idle → Idle"):
    assertEquals(
      OpencodeEvent.parse(
        """{"type":"session.idle","properties":{"sessionID":"ses_A"}}"""
      ),
      OpencodeEvent.Idle(Some("ses_A"))
    )

  test("a terminal frame without a sessionID still parses (treated as ours)"):
    assertEquals(
      OpencodeEvent.parse("""{"type":"session.idle","properties":{}}"""),
      OpencodeEvent.Idle(None)
    )

  test("session.error → Errored with the extracted message"):
    val e = OpencodeEvent.parse(
      """{"type":"session.error","properties":{"sessionID":"ses_A","error":{"data":{"message":"boom"}}}}"""
    )
    assertEquals(e, OpencodeEvent.Errored(Some("ses_A"), "boom"))

  test("question.asked → QuestionAsked"):
    val e = OpencodeEvent.parse(
      """{"type":"question.asked","properties":{"id":"que_1","sessionID":"ses_A","questions":[{"question":"Choose a color","header":"Color","options":[{"label":"Red","description":"the red"},{"label":"Blue","description":"the blue"}]}],"tool":{"messageID":"msg_1","callID":"toolu_1"}}}"""
    )
    e match
      case OpencodeEvent.QuestionAsked(req) =>
        assertEquals(req.id, "que_1")
        assertEquals(
          req.questions.head.options.map(_.label),
          List("Red", "Blue")
        )
      case other => fail(s"expected QuestionAsked, got $other")

  test("permission.asked → PermissionAsked"):
    val e = OpencodeEvent.parse(
      """{"type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_A","permission":"bash","patterns":["echo hi"],"always":["echo *"],"metadata":{},"tool":{"messageID":"msg_1","callID":"toolu_1"}}}"""
    )
    e match
      case OpencodeEvent.PermissionAsked(req) =>
        assertEquals(req.id, "per_1")
        assertEquals(req.permission, "bash")
        assertEquals(req.patterns, List("echo hi"))
      case other => fail(s"expected PermissionAsked, got $other")

  test("unmodelled event types → Ignored"):
    assertEquals(
      OpencodeEvent.parse("""{"type":"server.heartbeat","properties":{}}"""),
      OpencodeEvent.Ignored
    )
    assertEquals(
      OpencodeEvent.parse(
        """{"type":"session.status","properties":{"sessionID":"ses_A"}}"""
      ),
      OpencodeEvent.Ignored
    )

  test("malformed JSON throws"):
    intercept[Exception](OpencodeEvent.parse("not json"))

  test("sessionId lifts the owning session for filtering; Ignored has none"):
    assertEquals(OpencodeEvent.TextDelta("ses_A", "x").sessionId, Some("ses_A"))
    assertEquals(OpencodeEvent.Idle(Some("ses_B")).sessionId, Some("ses_B"))
    assertEquals(OpencodeEvent.Idle(None).sessionId, None)
    assertEquals(OpencodeEvent.Ignored.sessionId, None)
