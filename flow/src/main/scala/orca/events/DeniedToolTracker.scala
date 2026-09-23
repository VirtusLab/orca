package orca.events

import ox.Ox
import ox.channels.{Actor, ActorRef, BufferCapacity}

/** Listener that counts `ToolDenied` events per tool, with the names of the
  * agents that were denied it, and prints them as an end-of-run summary.
  */
class DeniedToolTracker private (actor: ActorRef[DeniedToolTracker.Tally])
    extends OrcaListener:

  def onEvent(event: OrcaEvent): Unit = event match
    case d: OrcaEvent.ToolDenied => actor.tell(_.add(d))
    case _                       => ()

  /** Print [[DeniedTools.summary]] on its own block, as
    * [[CostTracker.printSummary]] does. Prints nothing when nothing was denied.
    */
  def printSummary(): Unit =
    val s = actor.ask(_.summary)
    if s.nonEmpty then println(s"\n$s")

object DeniedToolTracker:

  /** A tracker whose tally is owned by an actor forked in the given scope,
    * which must span every `onEvent` through `printSummary`.
    */
  def start()(using Ox, BufferCapacity): DeniedToolTracker =
    new DeniedToolTracker(Actor.create(new Tally))

  // Only ever touched from the actor's thread.
  private class Tally:
    private var tools = DeniedTools.empty
    def add(d: OrcaEvent.ToolDenied): Unit = tools = tools.add(d)
    def summary: String = tools.summary

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
