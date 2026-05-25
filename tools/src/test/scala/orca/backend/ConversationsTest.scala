package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.Usage
import orca.llm.{BackendTag, SessionId}

import java.util.concurrent.atomic.AtomicInteger

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
