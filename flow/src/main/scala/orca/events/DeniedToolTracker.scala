package orca.events

import java.util.concurrent.atomic.AtomicReference

/** Listener that counts `ToolDenied` events per tool, with the names of the
  * agents that were denied it, and renders them as an end-of-run summary.
  *
  * Safe to call from concurrent forks. The state is an immutable value in an
  * `AtomicReference`, as in [[CostTracker]]: `onEvent` is synchronous and
  * called from foreign forks, so an actor or channel would need an owning scope
  * that outlives the listener.
  */
class DeniedToolTracker extends OrcaListener:

  private case class Denials(count: Int, agents: Set[String]):
    def add(agent: Option[String]): Denials =
      Denials(count + 1, agents ++ agent)

  private val byTool: AtomicReference[Map[String, Denials]] =
    AtomicReference(Map.empty)

  def onEvent(event: OrcaEvent): Unit = event match
    case d: OrcaEvent.ToolDenied =>
      val _ = byTool.updateAndGet(_.updatedWith(d.tool): prev =>
        Some(prev.getOrElse(Denials(0, Set.empty)).add(d.agent)))
    case _ => ()

  /** One line per denied tool, most-denied first, ties by tool name: `denied
    * tool calls: <tool> ×<n> (<agents>) — <advice>`. The agent list is
    * alphabetical and omitted when no denial carried an agent name. Empty
    * string when nothing was denied.
    */
  def summary: String =
    byTool
      .get()
      .toList
      .sortBy((tool, denials) => (-denials.count, tool))
      .map(formatLine)
      .mkString("\n")

  private def formatLine(tool: String, denials: Denials): String =
    val agents =
      if denials.agents.isEmpty then ""
      else denials.agents.toList.sorted.mkString(" (", ", ", ")")
    s"denied tool calls: $tool ×${denials.count}$agents — allow it in your " +
      "harness settings, or relax the instruction that requests it"

  /** Print the summary on its own block, as [[CostTracker.printSummary]] does.
    * Prints nothing when nothing was denied.
    */
  def printSummary(): Unit =
    val s = summary
    if s.nonEmpty then println(s"\n$s")
