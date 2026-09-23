package orca.shell.cli

import orca.shell.ShellEnv
import orca.shell.actions.{FlowResolution, ViewAction}

/** `orca view`'s behavior (ADR 0021 §10/§6): resolve the flow and print its
  * source, highlighted per the `--plain`/`--color`/auto-detect decision.
  */
private[cli] object ViewCli:

  def run(
      flowRef: String,
      plain: Boolean,
      color: Boolean,
      tty: Boolean
  )(using ShellEnv): Int =
    resolveHighlight(plain, color, tty) match
      case Left(message) =>
        Cli.diagnostic(message)
        ExitCodes.UsageError
      case Right(highlight) => runView(flowRef, highlight)

  /** `--plain`/`--color`'s resolution (ADR 0021 §10 fold-in): mutually
    * exclusive; either wins outright over the auto-detected `tty` — an explicit
    * flag is still the escape hatch for `view | less -R` (wants highlighting)
    * or a genuinely-a-terminal stdout piped through something that mangles ANSI
    * (wants none). `tty` is stdout-specific (production: `TtyProbe.stdout()`,
    * not the combined `System.console() != null` — stdin's own redirection is
    * irrelevant to whether stdout can render highlighting) and injected so
    * every branch is testable without a real console.
    */
  private[cli] def resolveHighlight(
      plain: Boolean,
      color: Boolean,
      tty: Boolean
  ): Either[String, Boolean] =
    if plain && color then Left("--plain and --color are mutually exclusive")
    else if plain then Right(false)
    else if color then Right(true)
    else Right(tty)

  /** `view`'s behavior once `highlight` is decided. */
  private[cli] def runView(flowRef: String, highlight: Boolean)(using
      ShellEnv
  ): Int =
    FlowResolution.resolve(flowRef) match
      case Left(message) =>
        Cli.diagnostic(message)
        ExitCodes.ActionFailed
      case Right(resolved) =>
        println(ViewAction.render(resolved, highlight))
        ExitCodes.Ok
