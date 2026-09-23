package orca.shell

import orca.shell.cli.{Cli, CliHelp}
import orca.shell.menu.{MenuContext, SettingsMenu, ShellMenu}
import orca.shell.ui.{ShellOutput, ShellUi}
import orca.shell.wizard.{FirstRun, FirstRunStatus, Wizard}
import orca.subprocess.PathProbe
import ox.discard

/** Entry point for the `orca` shell executable (ADR 0021). No-arg → the
  * interactive shell ([[ShellMenu]]). Any argv → the non-interactive CLI
  * surface (ADR 0021 §10, `cli/Cli.scala`): a curated `--help`/`--version`
  * handled here, a known subcommand dispatched to [[Cli.dispatch]] with its
  * returned code the sole `sys.exit` call, anything else a usage error. The CLI
  * path never prints the banner or runs the first-run wizard — both are
  * exclusive to the interactive shell.
  */
object Main:

  def main(args: Array[String]): Unit =
    given ShellEnv = ShellEnv.current()
    args.headOption match
      case None => runInteractiveShell()
      case Some("--help") | Some("-h") | Some("help") =>
        println(CliHelp.topLevel)
        sys.exit(0)
      case Some("--version") | Some("-V") =>
        println(OrcaBuild.current.version)
        sys.exit(0)
      case Some(token) if Cli.commandNames(token) =>
        sys.exit(Cli.dispatch(args.toIndexedSeq))
      case Some(token) =>
        Console.err.println(
          s"orca: unknown command '$token' — run 'orca --help'"
        )
        sys.exit(2)

  private def runInteractiveShell()(using env: ShellEnv): Unit =
    val terminal = ShellUi.buildTerminal()
    try
      val tty = ShellUi.isInteractive(terminal)
      // Clear stale mid-line progress bytes so the banner starts clean.
      print(ShellOutput.AnsiClearLine)
      ShellOutput.info(s"orca shell ${OrcaBuild.current.version}")
      OrcaBuild.current match
        case OrcaBuild.Snapshot(_) =>
          ShellOutput.info(
            "snapshot build — flows run on it from the local Ivy repository " +
              "(sbt publishLocal)"
          )
        case OrcaBuild.Release(_) => ()
      // scala-cli/coursier's download progress can still have several stale
      // lines sitting below the banner (see AnsiClearBelow) — wipe them before
      // the first wizard/menu paint. Non-tty output (NumberedUi) skips this:
      // there's no terminal to erase, only a redirected stream to pollute.
      if tty then print(ShellOutput.AnsiClearBelow)
      val ui = ShellUi.make(terminal)
      val wizard = Wizard(
        ui,
        PathProbe.resolves(_, env.workDir),
        env.configHome.settings
      )
      runWizardIfFirstRun(wizard, env.configHome.settings)
      SettingsMenu.printConfigSummary
      ShellMenu.loop(MenuContext(ui, wizard, terminal, tty))
    finally terminal.close()

  /** Runs the welcome wizard before the first menu when [[FirstRun.check]]
    * reports [[FirstRunStatus.FirstRun]] (ADR 0021 §4). A malformed global file
    * is NOT first-run: its parse error is surfaced here, and the
    * confirm-and-rewrite offer itself is [[Wizard.repairMalformed]].
    */
  private def runWizardIfFirstRun(
      wizard: Wizard,
      globalSettingsPath: os.Path
  ): Unit =
    FirstRun.check(globalSettingsPath) match
      case Right(FirstRunStatus.FirstRun) =>
        wizard.run(reconfigure = false).discard
      case Right(FirstRunStatus.AlreadyConfigured) => ()
      case Left(error) =>
        ShellOutput.error(
          s"the global settings file is malformed — ${error.message}"
        )
        wizard.repairMalformed()
