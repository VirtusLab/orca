package orca.testkit

import orca.OrcaInteractiveCancelled
import orca.agents.{BackendTag, StructuredOutputMode}
import orca.backend.{AgentResult, LiveTurn, TurnEvent}

import java.util.concurrent.atomic.AtomicInteger

/** A conversation that yields `scripted`, then answers `awaitResult` with
  * `outcome`: a `Left(OrcaInteractiveCancelled)` is returned, any other `Left`
  * is thrown, standing in for a turn that failed.
  */
class ScriptedTurn[B <: BackendTag](
    scripted: List[TurnEvent],
    outcome: Either[Throwable, AgentResult[B]],
    val outputSchema: Option[String] = None,
    override val structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText
) extends LiveTurn[B]:
  /** How many events a consumer pulled. */
  val drained = new AtomicInteger(0)
  val cancelCount = new AtomicInteger(0)
  def events: Iterator[TurnEvent] =
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
