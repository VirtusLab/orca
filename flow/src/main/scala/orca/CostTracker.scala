package orca

import java.util.concurrent.atomic.AtomicReference

/** Listener that accumulates `TokensUsed` events into a running total,
  * keyed by model. State is held in an AtomicReference so the tracker is
  * safe to register across concurrent LLM calls. Events that don't carry a
  * model name are folded under the `None` key.
  */
class CostTracker extends OrcaListener:
  private val state: AtomicReference[Map[Option[String], Usage]] =
    AtomicReference(Map.empty)

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.TokensUsed(model, u) =>
      val _ = state.updateAndGet: m =>
        m.updated(model, m.getOrElse(model, Usage.empty) + u)
    case _ => ()

  /** Usage accumulated across every model. */
  def total: Usage =
    state.get().values.foldLeft(Usage.empty)(_ + _)

  /** Per-model usage breakdown. Entries keyed with `None` came from calls
    * that did not advertise a model name.
    */
  def perModel: Map[Option[String], Usage] = state.get()

  def summary: String =
    val lines = perModel.toList
      .sortBy(_._1.getOrElse(""))
      .map: (model, u) =>
        val costStr = u.cost.map(c => s" (cost: $$${c.toString})").getOrElse("")
        val name = model.getOrElse("<unknown>")
        s"  $name: ${u.inputTokens} in, ${u.outputTokens} out$costStr"
    val header =
      val t = total
      val costStr = t.cost.map(c => s" (cost: $$${c.toString})").getOrElse("")
      s"Tokens used: ${t.inputTokens} in, ${t.outputTokens} out$costStr"
    if lines.isEmpty then header
    else s"$header\n${lines.mkString("\n")}"

  def printSummary(): Unit = println(summary)
