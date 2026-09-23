package orca.shell.menu

import org.jline.terminal.Terminal
import orca.shell.{ShellEnv, Tier}
import orca.shell.actions.{
  ConfigSummary,
  SettingsEditAction,
  StackAction,
  StackStatus
}
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}
import ox.discard

/** The menu's settings items (ADR 0021 §4/§8) and the startup config summary.
  */
private[shell] object SettingsMenu:

  /** The two-line configuration summary ([[ConfigSummary]]) — printed at
    * startup and again after a completed Re-configure or settings edit, so the
    * user sees what they'd be reconfiguring.
    */
  def printConfigSummary(using env: ShellEnv): Unit =
    ShellOutput.info(
      ConfigSummary.agentsLine(env.configHome.settings, env.workDir)
    )
    ShellOutput.info(ConfigSummary.stackLine(env.workDir))

  /** "Edit settings": tier prompt, create the file from its template if absent
    * ([[SettingsEditAction.ensureExists]]), open it in `spawnEditor`, then
    * re-parse it ([[SettingsEditAction.validate]]): malformed reports a
    * non-fatal warning and returns to the menu, valid reprints the config
    * summary so the edit's effect is visible immediately.
    */
  private[menu] def editSettings(
      ui: ShellUi,
      terminal: Terminal,
      spawnEditor: SpawnEditor
  )(using env: ShellEnv): Unit =
    val globalSettingsPath = env.configHome.settings
    pickSettingsTier(ui, globalSettingsPath).foreach: tier =>
      val path =
        SettingsEditAction.pathFor(tier, env.workDir, globalSettingsPath)
      SettingsEditAction.ensureExists(tier, path, env.workDir)
      spawnEditor(terminal, path).discard
      SettingsEditAction.validate(tier, env.workDir, globalSettingsPath) match
        case Left(error) => ShellOutput.error(error)
        case Right(_)    => printConfigSummary

  /** The Project/Global picker naming each tier's settings file. */
  private def pickSettingsTier(
      ui: ShellUi,
      globalSettingsPath: os.Path
  ): Option[Tier] =
    ui.select(
      "Edit settings for which tier:",
      List(
        Choice(Tier.Project, "Project (.orca/settings.properties)"),
        Choice(Tier.Global, s"Global ($globalSettingsPath)")
      )
    ).toOption

  /** "Clear stack settings (format/lint/test) — re-detected on the next flow
    * run" (ADR 0021 §8/§4): [[StackAction.status]] does the guarded read/parse
    * (a missing file, or one configuring no stack key, is a no-op with a
    * one-line explanation; an unparseable file aborts instead of being
    * surgically edited blind); on a live status
    * [[StackAction.clearIfConfirmed]] renders it, confirms, and calls
    * [[StackAction.clear]] — which strips the stack lines so the next flow run
    * (`FlowLifecycle.readSettings`) fires discovery again.
    */
  private[menu] def rediscoverStack(ui: ShellUi)(using env: ShellEnv): Unit =
    StackAction.status(env.workDir) match
      case Left(message) => ShellOutput.error(message)
      case Right(StackStatus.NoSettings | StackStatus.NoStackConfigured) =>
        ShellOutput.info(StackAction.noSettingsMessage)
      case Right(StackStatus.Present(stack, content)) =>
        StackAction.clearIfConfirmed(
          env.workDir,
          stack,
          content,
          () =>
            ui.confirm(StackAction.clearConfirmPrompt, default = false) match
              case UiOutcome.Selected(true) => true
              case _                        => false
        )
