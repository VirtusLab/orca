package orca.shell.cli

import orca.shell.actions.{StackAction, StackStatus}
import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}

/** `orca rediscover-stack`'s behavior (ADR 0021 §10/§8): clear the project's
  * discovered stack settings so the next flow run re-detects them, confirming
  * first (or requiring `--yes` off a terminal).
  */
private[cli] object StackCli:

  /** `rediscover-stack`'s full behavior over an explicit `workDir` — pulled out
    * of the `@main` method so tests can point it at a temp project instead of
    * the real `os.pwd`, and inject `tty` instead of a real console.
    */
  private[cli] def runRediscoverStack(
      workDir: os.Path,
      yes: Boolean,
      tty: Boolean
  ): Int =
    StackAction.status(workDir) match
      case Left(message) =>
        Cli.diagnostic(message)
        ExitCodes.ActionFailed
      case Right(StackStatus.NoSettings | StackStatus.NoStackLines) =>
        ShellOutput.info(StackAction.noSettingsMessage)
        ExitCodes.Ok
      case Right(StackStatus.Present(stack, content)) =>
        if yes then
          StackAction.clearIfConfirmed(workDir, stack, content, () => true)
          ExitCodes.Ok
        else if tty then confirmAndClear(workDir, stack, content)
        else
          StackAction.clearIfConfirmed(workDir, stack, content, () => false)
          Cli.diagnostic(
            "pass --yes to confirm clearing stack settings (non-interactive)"
          )
          ExitCodes.UsageError

  private def confirmAndClear(
      workDir: os.Path,
      stack: orca.StackSettings,
      content: String
  ): Int =
    Cli.withTerminal: terminal =>
      val ui = ShellUi.make(terminal)
      StackAction.clearIfConfirmed(
        workDir,
        stack,
        content,
        () =>
          ui.confirm(StackAction.clearConfirmPrompt, default = false) match
            case UiOutcome.Selected(true) => true
            case _                        => false
      )
      ExitCodes.Ok
