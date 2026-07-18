package orca.shell

import org.jline.terminal.TerminalBuilder
import orca.OrcaDir
import orca.settings.GlobalSettings
import orca.shell.flows.{BuiltInFlows, DiscoveredFlow, FlowCatalog, FlowEditor, FlowOrigin, FlowViewer}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.shell.wizard.{FirstRun, FirstRunStatus, Wizard}
import orca.subprocess.PathProbe
import ox.discard

import scala.annotation.tailrec

/** Entry point for the `orca` shell executable (ADR 0021). */
object Main:

  def main(args: Array[String]): Unit =
    println(s"orca shell ${ShellVersion.value}")
    val terminal = TerminalBuilder.builder().system(true).dumb(true).build()
    try
      val ui = ShellUi.make(terminal)
      val globalSettingsPath = GlobalSettings.default
      val wizard = Wizard(ui, PathProbe.resolves(_, os.pwd), globalSettingsPath)
      runWizardIfFirstRun(wizard, globalSettingsPath)
      loop(ui, wizard, ShellUi.isInteractive(terminal))
    finally terminal.close()

  /** Runs the welcome wizard before the first menu when [[FirstRun.check]]
    * reports [[FirstRunStatus.FirstRun]] (ADR 0021 §4). A malformed global
    * file is NOT first-run: its parse error is surfaced here, and the
    * confirm-and-rewrite offer itself is [[Wizard.repairMalformed]].
    */
  private def runWizardIfFirstRun(wizard: Wizard, globalSettingsPath: os.Path): Unit =
    FirstRun.check(globalSettingsPath) match
      case Right(FirstRunStatus.FirstRun)          => wizard.run(reconfigure = false).discard
      case Right(FirstRunStatus.AlreadyConfigured) => ()
      case Left(error) =>
        println(s"orca: the global settings file is malformed — ${error.message}")
        wizard.repairMalformed()

  /** Runs the main menu until Exit is chosen or the top-level prompt is
    * cancelled (Ctrl-C / EOF); every non-Exit, non-Reconfigure, non-flow item
    * is a stub until its epic lands. Continue a session is hardcoded disabled
    * until session tracking (ADR 0021 §8) lands and can report whether any
    * session actually exists.
    */
  @tailrec private def loop(ui: ShellUi, wizard: Wizard, tty: Boolean): Unit =
    val continueDisabledReason = Some("no sessions recorded yet")
    ui.select("orca shell", MainMenu.choices(continueDisabledReason)) match
      case UiOutcome.Cancelled                      => ()
      case UiOutcome.Selected(MenuItem.Exit)        => ()
      case UiOutcome.Selected(MenuItem.Reconfigure) =>
        wizard.run(reconfigure = true).discard
        loop(ui, wizard, tty)
      case UiOutcome.Selected(MenuItem.ViewFlow) =>
        viewFlow(ui, tty)
        loop(ui, wizard, tty)
      case UiOutcome.Selected(MenuItem.EditFlow) =>
        editFlow(ui)
        loop(ui, wizard, tty)
      case UiOutcome.Selected(item) =>
        println(s"$item: not implemented yet")
        loop(ui, wizard, tty)

  /** Prints the chosen flow's source (highlighted when `tty`) and returns —
    * the menu redraws on the next loop iteration, so no pager is needed
    * (ADR 0021 §6).
    */
  private def viewFlow(ui: ShellUi, tty: Boolean): Unit =
    selectFlow(ui, "View which flow?").foreach: flow =>
      println(FlowViewer.render(os.read(flow.path), tty))

  /** Opens the chosen flow in `$VISUAL`/`$EDITOR`/`vi`. Project and global
    * flows are edited in place; a built-in is never edited in its cache
    * copy, so [[customizeThenEditPath]] offers to copy it into a tier first.
    */
  private def editFlow(ui: ShellUi): Unit =
    selectFlow(ui, "Edit which flow?").foreach: flow =>
      val path =
        if flow.origin != FlowOrigin.BuiltIn then Some(flow.path)
        else customizeThenEditPath(ui, flow)
      path.foreach(p => FlowEditor.edit(FlowEditor.resolveEditor(sys.env.get), p).discard)

  /** Prompts for Project or Global, copies the built-in there via
    * [[FlowEditor.customizeTarget]], and returns the copy's path — `None` on
    * Cancelled or on a name collision (reported and left unedited).
    */
  private def customizeThenEditPath(ui: ShellUi, flow: DiscoveredFlow): Option[os.Path] =
    val globalFlows = GlobalSettings.defaultFlows
    val tierChoices = List(
      Choice(FlowOrigin.Project, "Project (.orca/flows/)"),
      Choice(FlowOrigin.Global, s"Global ($globalFlows)")
    )
    ui.select(s"'${flow.name}' is built-in — customize it into", tierChoices) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(tier) =>
        FlowEditor.customizeTarget(flow, tier, os.pwd, globalFlows) match
          case Left(message) =>
            println(s"orca: $message")
            None
          case Right(path) => Some(path)

  private def selectFlow(ui: ShellUi, title: String): Option[DiscoveredFlow] =
    val flows = FlowCatalog.list(
      OrcaDir.flowsPath(os.pwd),
      GlobalSettings.defaultFlows,
      BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value)
    )
    ui.select(title, flows.map(flowChoice)) match
      case UiOutcome.Cancelled  => None
      case UiOutcome.Selected(flow) => Some(flow)

  /** `name — description [origin]`, with a `[shadows ...]` suffix when the
    * winner shadows a lower-precedence tier (ADR 0021 §5); the description
    * is also carried on [[Choice.description]].
    */
  private def flowChoice(flow: DiscoveredFlow): Choice[DiscoveredFlow] =
    val shadows =
      if flow.shadows.isEmpty then ""
      else s" [shadows ${flow.shadows.map(originLabel).mkString(", ")}]"
    val description = flow.description.getOrElse("(no description)")
    val label = s"${flow.name} — $description [${originLabel(flow.origin)}]$shadows"
    Choice(flow, label, description = flow.description)

  private def originLabel(origin: FlowOrigin): String = origin match
    case FlowOrigin.Project => "project"
    case FlowOrigin.Global  => "global"
    case FlowOrigin.BuiltIn => "built-in"
