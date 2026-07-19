package orca.shell.actions

import orca.shell.flows.{DiscoveredFlow, FlowViewer}

/** Renders a resolved flow's source (ADR 0021 §6). Thin: the actual work is
  * [[FlowViewer.render]] — kept as its own action so a non-interactive caller
  * has the same one-line call the menu's `viewFlow` makes.
  */
private[shell] object ViewAction:
  def render(flow: DiscoveredFlow, tty: Boolean): String =
    FlowViewer.render(os.read(flow.path), tty)
