package orca

import java.util.concurrent.atomic.AtomicReference

/** Listener that accumulates `TokensUsed` events into a running total. The
  * running `Usage` is held in an AtomicReference so the tracker is safe to
  * register across concurrent LLM calls. This is a stateful observer — state
  * persists across `onEvent` calls by necessity, with a pure transition
  * (`Usage.+`) applied under a CAS loop.
  */
// TODO: this should be tracked per-model
class CostTracker extends OrcaListener:
  private val state: AtomicReference[Usage] = AtomicReference(Usage.empty)

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.TokensUsed(u) =>
      val _ = state.updateAndGet(_ + u)
    case _ => ()

  def total: Usage = state.get()

  def summary: String =
    val u = total
    val costStr = u.cost.map(c => s" (cost: $$${c.toString})").getOrElse("")
    s"Tokens used: ${u.inputTokens} in, ${u.outputTokens} out$costStr"

  def printSummary(): Unit = println(summary)
