package orca

import java.util.concurrent.atomic.AtomicReference

/** Listener that accumulates `TokensUsed` events into a running total, keyed by
  * model. State is held in an AtomicReference so the tracker is safe to
  * register across concurrent LLM calls. The model key is always a real string
  * per [[OrcaEvent.TokensUsed]]'s contract — no `<unknown>` bucket needed.
  */
class CostTracker extends OrcaListener:
  private val state: AtomicReference[Map[String, Usage]] =
    AtomicReference(Map.empty)

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.TokensUsed(model, u) =>
      val _ = state.updateAndGet: m =>
        m.updated(model, m.getOrElse(model, Usage.empty) + u)
    case _ => ()

  /** Usage accumulated across every model. */
  def total: Usage =
    state.get().values.foldLeft(Usage.empty)(_ + _)

  /** Per-model usage breakdown. */
  def perModel: Map[String, Usage] = state.get()

  /** Per-model token tallies, one line each, sorted by model name. Empty when
    * no `TokensUsed` events have been observed.
    */
  def summary: String =
    perModel.toList
      .sortBy(_._1)
      .map: (model, u) =>
        s"$model: ${u.inputTokens} in, ${u.outputTokens} out"
      .mkString("\n")

  /** Print the summary on its own block. Leading newline keeps the output from
    * landing on top of an active terminal status row; trailing newline ensures
    * the last line is committed.
    */
  def printSummary(): Unit =
    val s = summary
    if s.nonEmpty then println(s"\n$s")
