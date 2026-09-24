package orca.events

import java.util.concurrent.atomic.AtomicReference

/** Listener that counts `ToolDenied` events per tool, with the names of the
  * agents that were denied it, for an end-of-run summary.
  */
class DeniedToolTracker extends OrcaListener:

  private val tools: AtomicReference[DeniedTools] =
    AtomicReference(DeniedTools.empty)

  def onEvent(event: OrcaEvent): Unit = event match
    case d: OrcaEvent.ToolDenied =>
      val _ = tools.updateAndGet(_.add(d))
    case _ => ()

  /** [[DeniedTools.summary]] of the events seen so far. */
  def summary: String = tools.get().summary

/** Denied tool calls counted per tool, with the names of the agents that were
  * denied it.
  */
private[orca] case class DeniedTools(byTool: Map[String, DeniedTools.Denials]):

  def add(d: OrcaEvent.ToolDenied): DeniedTools =
    DeniedTools(byTool.updatedWith(d.tool): prev =>
      Some(prev.getOrElse(DeniedTools.Denials(0, Set.empty)).add(d.agent)))

  /** One line per denied tool, most-denied first, ties by tool name: `denied
    * tool calls: <tool> ×<n> (<agents>) — <advice>`. The agent list is
    * alphabetical and omitted when no denial carried an agent name. Empty
    * string when nothing was denied.
    */
  def summary: String =
    byTool.toList
      .sortBy((tool, denials) => (-denials.count, tool))
      .map(formatLine)
      .mkString("\n")

  private def formatLine(tool: String, denials: DeniedTools.Denials): String =
    val agents =
      if denials.agents.isEmpty then ""
      else denials.agents.toList.sorted.mkString(" (", ", ", ")")
    s"denied tool calls: $tool ×${denials.count}$agents — allow it in your " +
      "harness settings, or relax the instruction that requests it"

private[orca] object DeniedTools:
  val empty: DeniedTools = DeniedTools(Map.empty)

  case class Denials(count: Int, agents: Set[String]):
    def add(agent: Option[String]): Denials =
      Denials(count + 1, agents ++ agent)
