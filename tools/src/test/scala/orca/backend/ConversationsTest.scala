package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener, Usage}
import orca.llm.{BackendTag, SessionId}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

private class ScriptedConversation(
    eventList: List[ConversationEvent],
    outcome: Either[OrcaInteractiveCancelled, LlmResult[BackendTag.Codex.type]]
) extends Conversation[BackendTag.Codex.type]:
  val drained = new AtomicInteger(0)
  val outputSchema: Option[String] = None
  val events: Iterator[ConversationEvent] = eventList.iterator.map { e =>
    val _ = drained.incrementAndGet()
    e
  }
  def awaitResult()
      : Either[OrcaInteractiveCancelled, LlmResult[BackendTag.Codex.type]] =
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

  private val sampleResult = LlmResult[BackendTag.Codex.type](
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

  test("AssistantToolCall emits OrcaEvent.ToolUse with the truncated input"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantToolCall("Bash", """{"cmd":"ls"}""")),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(
      recorder.events,
      List(OrcaEvent.ToolUse("Bash", """{"cmd":"ls"}"""))
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

  test("ConversationEvent.Error re-emits as OrcaEvent.Error"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ = Conversations.drainAutonomous(conv, recorder)
    assertEquals(recorder.events, List(OrcaEvent.Error("boom")))

  test("ToolResult and ThinkingDelta are swallowed"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantThinkingDelta("thinking..."),
        ConversationEvent.ToolResult("Bash", ok = true, "output")
      ),
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
