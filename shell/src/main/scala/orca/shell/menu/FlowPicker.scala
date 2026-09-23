package orca.shell.menu

import orca.shell.ShellEnv
import orca.shell.actions.FlowResolution
import orca.shell.flows.DiscoveredFlow
import orca.shell.ui.{Choice, ShellOutput, ShellUi}

/** Picks a flow from the three-tier catalog (ADR 0021 §5). */
private[menu] object FlowPicker:

  /** Lists flows via [[FlowResolution.list]] — any failure (a committed symlink
    * guard tripping, or built-in extraction hitting a full-disk/permission
    * error) is reported and the caller gets `None`.
    */
  def listFlows(using ShellEnv): Option[List[DiscoveredFlow]] =
    FlowResolution.list match
      case Left(message) =>
        ShellOutput.error(message)
        None
      case Right(flows) => Some(flows)

  /** Shows the catalog via [[pickFlow]] at its default (alphabetical) order — a
    * discovery failure is reported and the caller gets `None`, same as
    * Cancelled, so the menu redraws instead of the shell crashing.
    */
  def selectFlow(ui: ShellUi, title: String)(using
      ShellEnv
  ): Option[DiscoveredFlow] =
    listFlows.flatMap(pickFlow(ui, title, _))

  /** Shows `flows` via `ui.select`, `default` first. */
  def pickFlow(
      ui: ShellUi,
      title: String,
      flows: List[DiscoveredFlow],
      default: Option[DiscoveredFlow] = None
  ): Option[DiscoveredFlow] =
    ui.select(title, flows.map(flowChoice), default).toOption

  /** `name — description [origin]`, with a `[shadows ...]` suffix when the
    * winner shadows a lower-precedence tier (ADR 0021 §5).
    */
  private def flowChoice(flow: DiscoveredFlow): Choice[DiscoveredFlow] =
    val shadows =
      if flow.shadows.isEmpty then ""
      else s" [shadows ${flow.shadows.map(_.label).mkString(", ")}]"
    val description = flow.description.getOrElse("(no description)")
    val label =
      s"${flow.name} — $description [${flow.origin.label}]$shadows"
    Choice(flow, label)
