package orca.shell.actions

import org.jline.terminal.Terminal
import orca.shell.create.CreateTier
import orca.shell.flows.{DiscoveredFlow, FlowEditor}
import orca.shell.run.ChildTerminal

/** Opens a flow in the user's editor (ADR 0021 §6) — the exec half of
  * `Main.editFlow`; the tier prompt for a built-in flow (which tier to
  * customize into) stays in `Main` since [[customizeThenEdit]] takes the tier
  * already chosen.
  */
private[shell] object EditAction:

  /** Spawns `$VISUAL`/`$EDITOR`/`vi` on `path` under
    * [[ChildTerminal.withChild]] (ADR 0021 §2), returning the editor's exit
    * code.
    */
  def editInPlace(terminal: Terminal, path: os.Path): Int =
    ChildTerminal.withChild(terminal)(
      FlowEditor.edit(FlowEditor.resolveEditor(sys.env.get), path)
    )

  /** Copies a built-in flow into `tier` ([[FlowEditor.customizeTarget]]) then
    * opens the copy via [[editInPlace]]. Left on a filename collision, without
    * ever spawning an editor.
    */
  def customizeThenEdit(
      terminal: Terminal,
      flow: DiscoveredFlow,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, Int] =
    FlowEditor
      .customizeTarget(flow, tier, workDir, globalFlows)
      .map(path => editInPlace(terminal, path))
