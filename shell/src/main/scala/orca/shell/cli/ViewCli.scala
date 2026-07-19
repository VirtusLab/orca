package orca.shell.cli

import orca.shell.actions.{FlowResolution, ViewAction}

/** `orca view`'s behavior (ADR 0021 §10/§6): resolve the flow and print its
  * source, highlighted per the `--plain`/`--color`/auto-detect decision.
  */
private[cli] object ViewCli:

  def run(
      flowRef: String,
      plain: Boolean,
      color: Boolean,
      tty: Boolean,
      workDir: os.Path
  ): Int =
    resolveHighlight(plain, color, tty) match
      case Left(message) =>
        Cli.fail(message)
        ExitCodes.UsageError
      case Right(highlight) => runView(workDir, flowRef, highlight)

  /** `--plain`/`--color`'s resolution (ADR 0021 §10 fold-in): mutually
    * exclusive; either wins outright over the auto-detected `tty` — JDK 21 has
    * no clean stdout-only tty probe, so an explicit flag is the escape hatch
    * for `view | less -R` (wants highlighting) or a genuinely-a-terminal stdout
    * piped through something that mangles ANSI (wants none). `tty` is injected
    * (production: `Cli.isTty`) so every branch is testable without a real
    * console.
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

  /** `view`'s full behavior over an explicit `workDir` and resolved `highlight`
    * decision — pulled out of the `@main` method so tests can point it at a
    * temp project and pass a resolved boolean directly, without touching the
    * real console.
    */
  private[cli] def runView(
      workDir: os.Path,
      flowRef: String,
      highlight: Boolean
  ): Int =
    FlowResolution.resolve(flowRef, workDir) match
      case Left(message) =>
        Cli.fail(message)
        ExitCodes.ActionFailed
      case Right(resolved) =>
        println(ViewAction.render(resolved, highlight))
        ExitCodes.Ok
