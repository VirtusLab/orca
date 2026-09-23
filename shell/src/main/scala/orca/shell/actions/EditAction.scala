package orca.shell.actions

import org.jline.terminal.Terminal
import orca.shell.{ShellEnv, Tier}
import orca.shell.flows.{DiscoveredFlow, FlowEditor}
import orca.shell.run.ChildTerminal

/** Opens a flow in the user's editor (ADR 0021 §6) — the exec half of the
  * menu's and the CLI's edit paths, which pick the tier for a built-in flow.
  */
private[shell] object EditAction:

  /** Spawns `$VISUAL`/`$EDITOR`/`vi` on `path` under
    * [[ChildTerminal.withChild]] (ADR 0021 §2), returning the editor's exit
    * code.
    */
  def editInPlace(terminal: Terminal, path: os.Path)(using env: ShellEnv): Int =
    ChildTerminal.withChild(terminal)(
      FlowEditor.edit(FlowEditor.resolveEditor(env.vars), path)
    )

  /** Copies a built-in flow into `tier` ([[FlowEditor.customizeTarget]]) then
    * opens the copy via [[editInPlace]]. Left on a filename collision, without
    * ever spawning an editor.
    */
  def customizeThenEdit(
      terminal: Terminal,
      flow: DiscoveredFlow,
      tier: Tier
  )(using ShellEnv): Either[String, Int] =
    FlowEditor
      .customizeTarget(flow, tier)
      .map(path => editInPlace(terminal, path))
