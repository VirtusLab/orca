package orca.shell.menu

import org.jline.terminal.Terminal
import orca.shell.{ShellEnv, WorktreeScan}
import orca.shell.actions.{ConfigSummary, EditAction, SessionAction, ViewAction}
import orca.shell.resume.{InterruptedRun, ResumeDetector}
import orca.shell.run.FlowLauncher
import orca.shell.sessions.{
  AttemptListing,
  ManifestReader,
  ObservedStatus,
  SessionIndex,
  SessionPicker
}
import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}
import orca.shell.wizard.Wizard

import scala.annotation.tailrec

/** What every menu action draws on. */
private[shell] case class MenuContext(
    ui: ShellUi,
    wizard: Wizard,
    terminal: Terminal,
    tty: Boolean
)

/** The interactive shell's main menu (ADR 0021 §3). */
private[shell] object ShellMenu:

  /** Runs the main menu until Exit is chosen or the top-level prompt is
    * cancelled (Ctrl-C / EOF). Continue a session re-reads
    * `.orca/cache/attempts/` on every redraw (ADR 0021 §8) — a flow run started
    * from this same menu can only have just finished, so the freshest listing
    * is worth the re-read. `ResumeDetector.detect` is likewise re-evaluated
    * every redraw (ADR 0021 §3 amendment). Both scans share ONE
    * `WorktreeScan.dirs` resolution — the discovery is a git subprocess or two,
    * and nothing between them can change the answer — over a bounded set of
    * directories, so a redraw stays cheap enough to repeat and consistent with
    * Continue's own re-read. Re-discovering per redraw is the point: a
    * `--worktree` run started from this very menu creates a worktree that was
    * not there when the shell started. The `branch:` line
    * ([[ConfigSummary.branchLine]]) is printed here for the same reason: a flow
    * run started from this menu can leave HEAD on a new branch, so it is
    * re-read per redraw rather than printed once with the startup summary.
    */
  @tailrec def loop(context: MenuContext)(using env: ShellEnv): Unit =
    val scanDirs = WorktreeScan.dirs(env.workDir)
    val AttemptListing(attempts, warnings) =
      ManifestReader.list(
        scanDirs.own,
        scanDirs.worktrees,
        ObservedStatus.processAlive
      )
    warnings.foreach(ShellOutput.info)
    val continueSessionCount =
      attempts.headOption.map(_.manifest.sessions.size)
    val resumeOffer = ResumeDetector.detect(scanDirs.all)
    ConfigSummary.branchLine(env.workDir).foreach(ShellOutput.info)
    context.ui.select(
      "orca shell",
      MainMenu.choices(continueSessionCount, resumeOffer, scanDirs.own)
    ) match
      case UiOutcome.Cancelled | UiOutcome.Selected(MenuItem.Exit) => ()
      case UiOutcome.Selected(item) =>
        handle(item, context, resumeOffer, SessionIndex.of(attempts))
        loop(context)

  private def handle(
      item: MenuItem,
      context: MenuContext,
      resumeOffer: Option[InterruptedRun],
      sessions: SessionIndex
  )(using ShellEnv): Unit =
    import context.{terminal, ui}
    val spawnEditor: SpawnEditor = EditAction.editInPlace
    val launch: FlowLauncher.FlowLaunch = FlowLauncher.runAnnounced
    item match
      case MenuItem.Reconfigure =>
        // Reprint the summary only when the wizard actually wrote new values
        // (`run` returns false on cancel).
        if context.wizard.run(reconfigure = true) then
          SettingsMenu.printConfigSummary
      case MenuItem.ResumeRun =>
        // The item is only in the menu when `resumeOffer` is `Some`.
        resumeOffer.foreach(
          RunMenu.resumeInterruptedRun(ui, terminal, _, launch)
        )
      case MenuItem.EditSettings =>
        SettingsMenu.editSettings(ui, terminal, spawnEditor)
      case MenuItem.RediscoverStack => SettingsMenu.rediscoverStack(ui)
      case MenuItem.ViewFlow        => viewFlow(ui, context.tty)
      case MenuItem.EditFlow =>
        AuthoringMenu.editFlow(ui, terminal, spawnEditor)
      case MenuItem.RunFlow => RunMenu.runFlow(ui, terminal, launch)
      case MenuItem.CreateFlow =>
        AuthoringMenu.createNewFlow(ui, terminal, spawnEditor)
      case MenuItem.ForkFlow =>
        AuthoringMenu.createForkFlow(ui, terminal, spawnEditor)
      case MenuItem.ContinueSession =>
        continueSession(ui, terminal, sessions, expanded = false)
      case MenuItem.Exit => ()

  /** Prints the chosen flow's source (highlighted when `tty`) and returns — the
    * menu redraws on the next loop iteration, so no pager is needed (ADR 0021
    * §6).
    */
  private def viewFlow(ui: ShellUi, tty: Boolean)(using ShellEnv): Unit =
    FlowPicker
      .selectFlow(ui, "View which flow?")
      .foreach: flow =>
        println(ViewAction.render(flow, tty))

  /** Prompts among every session in `index` and resumes the chosen one,
    * printing its identity — including `workDir` — before the resume exec
    * ([[SessionAction.identityNotice]], ADR 0021 §10; the CLI's own resume
    * paths print the same notice). Picking the expander re-renders the same
    * picker with `expanded = true`; there is no way back to the collapsed view
    * short of re-opening the menu item, which is fine — the picker is re-read
    * from disk on every open anyway. A cancelled prompt is a silent no-op.
    */
  private def continueSession(
      ui: ShellUi,
      terminal: Terminal,
      index: SessionIndex,
      expanded: Boolean
  ): Unit =
    ui.select(
      "Continue which session?",
      SessionPicker.sessionRows(index, expanded)
    ) match
      case UiOutcome.Cancelled => ()
      case UiOutcome.Selected(SessionPicker.PickerRow.ShowMore) =>
        continueSession(ui, terminal, index, expanded = true)
      case UiOutcome.Selected(SessionPicker.PickerRow.Resume(selection)) =>
        ShellOutput.info(SessionAction.resumeNotice(selection))
        SessionAction.resume(terminal, selection) match
          case Left(message) => ShellOutput.error(message)
          case Right(_)      => ()
