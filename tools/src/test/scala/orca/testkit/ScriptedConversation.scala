package orca.testkit

import orca.OrcaInteractiveCancelled
import orca.agents.{BackendTag, StructuredOutputMode}
import orca.backend.{AgentResult, Conversation, ConversationEvent}

import java.util.concurrent.atomic.AtomicInteger

/** A conversation that yields `scripted`, then answers `awaitResult` with
  * `outcome`: a `Left(OrcaInteractiveCancelled)` is returned, any other `Left`
  * is thrown, standing in for a turn that failed.
  */
class ScriptedConversation[B <: BackendTag](
    scripted: List[ConversationEvent],
    outcome: Either[Throwable, AgentResult[B]],
    val outputSchema: Option[String] = None,
    override val structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText
) extends Conversation[B]:
  /** How many events a consumer pulled. */
  val drained = new AtomicInteger(0)
  val cancelCount = new AtomicInteger(0)
  def events: Iterator[ConversationEvent] =
    scripted.iterator.map: e =>
      val _ = drained.incrementAndGet()
      e
  def awaitResult(): Either[OrcaInteractiveCancelled, AgentResult[B]] =
    outcome match
      case Right(r)                          => Right(r)
      case Left(c: OrcaInteractiveCancelled) => Left(c)
      case Left(t)                           => throw t
  def canAskUser: Boolean = false
  def cancel(): Unit =
    val _ = cancelCount.incrementAndGet()
