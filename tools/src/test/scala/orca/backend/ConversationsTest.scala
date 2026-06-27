package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener, Usage}
import orca.agents.{BackendTag, SessionId}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

private class ScriptedConversation(
    eventList: List[ConversationEvent],
    outcome: Either[OrcaInteractiveCancelled, AgentResult[
      BackendTag.Codex.type
    ]],
    val outputSchema: Option[String] = None
) extends Conversation[BackendTag.Codex.type]:
  val drained = new AtomicInteger(0)
  val events: Iterator[ConversationEvent] = eventList.iterator.map { e =>
    val _ = drained.incrementAndGet()
    e
  }
  def awaitResult()
      : Either[OrcaInteractiveCancelled, AgentResult[BackendTag.Codex.type]] =
    outcome
  def sendUserMessage(text: String): Unit = ()
  def canAskUser: Boolean = false
  def cancel(): Unit = ()

/** Records every `OrcaEvent` it sees so tests can assert on the emission order
  * without scaffolding a full listener.
  */
private class RecordingListener extends OrcaListener:
  private val log = new AtomicReference[List[OrcaEvent]](Nil)
  def events: List[OrcaEvent] = log.get().reverse
  def onEvent(event: OrcaEvent): Unit =
    val _ = log.updateAndGet(event :: _)

class ConversationsTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    sessionId = SessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage(0L, 0L, None)
  )

  test("drainAutonomous walks every event before returning the result"):
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("hi"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    assertEquals(Conversations.drainAutonomous(conv), sampleResult)
    assertEquals(conv.drained.get(), 2)

  test("drainAutonomous throws OrcaInteractiveCancelled on Left outcome"):
    val cancelled = new OrcaInteractiveCancelled()
    val conv = new ScriptedConversation(Nil, Left(cancelled))
    val thrown = intercept[OrcaInteractiveCancelled]:
      Conversations.drainAutonomous(conv)
    assertEquals(thrown, cancelled)

  test("AssistantToolCall emits OrcaEvent.ToolUse with the raw input"):
    // The drain hands the raw JSON through unchanged; the terminal listener
    // does summarisation so non-terminal listeners (structured logs, Slack)
    // see the full input.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantToolCall(
          "Bash",
          """{ "cmd" : "ls",  "extra" : "ignored" }"""
        )
      ),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.ToolUse("Bash", """{ "cmd" : "ls",  "extra" : "ignored" }""")
      )
    )

  test(
    "buffered text deltas flush as one AssistantMessage on AssistantTurnEnd"
  ):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("hello "),
        ConversationEvent.AssistantTextDelta("world"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("hello world"))
    )

  test("empty turn (TurnEnd with no deltas) emits nothing"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantTurnEnd),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, Nil)

  test("ApproveTool auto-denies and surfaces an Error"):
    // The autonomous drain has no user to ask, but the subprocess is
    // blocked on stdin waiting for our decision. Auto-denying with a
    // reason unblocks the agent (it can adapt); the Error event lets the
    // user see what got blocked. Silently dropping would deadlock.
    val recorder = new RecordingListener
    val decisions = new AtomicReference[List[ApprovalDecision]](Nil)
    val record = (d: ApprovalDecision) =>
      val _ = decisions.updateAndGet(d :: _)
    val conv = new ScriptedConversation(
      List(
        ConversationEvent
          .ApproveTool("Bash", """{"command":"rm -rf /"}""", record)
      ),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    decisions.get() match
      case ApprovalDecision.Deny(Some(reason)) :: Nil =>
        assert(reason.contains("Bash"), reason)
        assert(reason.contains("auto-approve"), reason)
      case other => fail(s"expected Deny with reason; got $other")
    assertEquals(
      recorder.events.collect { case e: OrcaEvent.Error => e.message },
      List(
        "Denied Bash: not in auto-approve set (autonomous mode cannot prompt)"
      )
    )

  test("UserQuestion auto-answers a placeholder and surfaces an Error"):
    // Defensive: the autonomous path never wires the ask_user MCP bridge,
    // so this event should be unreachable. If a future change ever lands
    // one here, the bridge thread is blocked on `respond` — answer with
    // a placeholder rather than leaking it.
    val recorder = new RecordingListener
    val answers = new AtomicReference[List[String]](Nil)
    val record = (s: String) =>
      val _ = answers.updateAndGet(s :: _)
    val conv = new ScriptedConversation(
      List(ConversationEvent.UserQuestion("What now?", record)),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    answers.get() match
      case ans :: Nil => assert(ans.contains("autonomous mode"), ans)
      case other      => fail(s"expected one answer; got $other")
    assert(
      recorder.events.exists {
        case OrcaEvent.Error(msg) => msg.contains("ask_user")
        case _                    => false
      },
      recorder.events
    )

  test("ToolResult is swallowed (already surfaced via the preceding ToolUse)"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.ToolResult("Bash", ok = true, "stdout text")),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, Nil)

  test("UserMessage echo is swallowed (UserPrompt covers it upstream)"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.UserMessage("echo of the opening prompt")),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, Nil)

  test("ConversationEvent.Error re-emits as OrcaEvent.Error"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, List(OrcaEvent.Error("boom")))

  test("AssistantThinkingDelta is swallowed"):
    // Thinking deltas go through their own explicit case branch (not the
    // catch-all), so they need their own test — otherwise removing the
    // case wouldn't break anything.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantThinkingDelta("thinking...")),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, Nil)

  test(
    "deltas without a trailing TurnEnd still flush at end-of-stream"
  ):
    // Mid-turn subprocess crash: deltas arrive, then EOF before TurnEnd.
    // Without the safety flush the partial agent message would be lost.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("half-finished thou"),
        ConversationEvent.AssistantTextDelta("ght")
      ),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("half-finished thought"))
    )

  test(
    "structured mode drops a mid-turn-crash partial (no closing TurnEnd)"
  ):
    // Mid-turn subprocess crash in structured mode: deltas arrive, then EOF
    // before TurnEnd. Outside structured mode we flush the partial so the
    // user can see what the agent had built up. Inside structured mode it
    // would only ever be a half-formed JSON payload — drop it; the thrown
    // exception (not modelled here) carries the real diagnostic.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("""{"answer":"""),
        ConversationEvent.AssistantTextDelta("""1""")
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, Nil)

  test(
    "structured mode drops the final turn's text (the JSON payload)"
  ):
    // In structured mode the agent's last assistant message IS the JSON
    // payload that the caller will surface via OrcaEvent.StructuredResult.
    // Emitting it here would double-render the result. Intermediate turns
    // still flush so the user sees the agent's prose along the way.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("planning..."),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("""{"answer":42}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("planning..."))
    )

  test("two back-to-back turns flush independently"):
    // Pins the textBuf.clear() inside the AssistantTurnEnd case so the
    // second turn doesn't carry the first turn's text.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("turn one"),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("turn two"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage("turn one"),
        OrcaEvent.AssistantMessage("turn two")
      )
    )
