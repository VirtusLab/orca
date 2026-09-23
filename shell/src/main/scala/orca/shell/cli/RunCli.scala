package orca.shell.cli

import orca.RawArgs
import orca.shell.ShellEnv
import orca.shell.actions.{FlowResolution, RunAction}
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchedFlow}

import Cli.{actionFailure, complete, usageFailure, withTerminal}

/** `orca run`'s behavior (ADR 0021 §10): resolve the flow, read the task
  * (argument, `--prompt` or piped stdin), then either the forced run
  * ([[RunAction.run]]) or the pin-honouring one
  * ([[FlowLauncher.runHonoringPin]]), propagating the flow child's raw exit
  * code.
  */
private[cli] object RunCli:

  /** `args` arrives unchecked. A task given twice or an invalid flag is refused
    * first, before anything is resolved, stdin is read or `scala-cli` starts;
    * the flow child refuses the same argv on the same shared decision
    * ([[orca.RawArgs.checked]]) and stays the authority, this only makes the
    * answer immediate.
    */
  def run(
      flowRef: String,
      args: RawArgs,
      honorPin: Boolean,
      tty: Boolean
  )(using env: ShellEnv): Int =
    complete:
      for
        checked <- args.checked.left.map(usageFailure)
        resolved <- FlowResolution
          .resolve(flowRef)
          .left
          .map(actionFailure)
        task <- readTask(checked.givenTask, tty, readAllStdin).left
          .map(usageFailure)
      yield withTerminal: terminal =>
        val orcaArgs = checked.withTask(task)
        val result =
          if honorPin then
            FlowLauncher.runHonoringPin(
              LaunchedFlow.of(resolved),
              orcaArgs,
              env.workDir,
              terminal
            )
          else
            RunAction.run(
              resolved,
              RunAction.RunOptions(
                args = orcaArgs,
                fallback = FallbackPolicy.Refuse("re-run with --honor-pin")
              ),
              env.workDir,
              terminal,
              FlowLauncher.runAnnounced
            )
        // propagates the flow child's raw exit code (LaunchResult.Failed's
        // exit, via Cli.exitCodeFor) — run mirrors a wrapped subprocess's
        // status rather than the flat 0/1/2 usage-error convention.
        Cli.exitCodeFor(result)

  private def readAllStdin(): String =
    String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)

  /** `task` when non-blank; `readStdin()` read to EOF when `task` is omitted
    * and `tty` is false (`enables generate-prompt | orca run fix.sc`); a usage
    * error otherwise — never blocks waiting on a terminal that has no task
    * coming. `tty` is stdin-specific (production: `TtyProbe.stdin()`, not the
    * combined `System.console() != null` — a redirected stdout alone, e.g.
    * `orca run flow.sc > out.log` from a real terminal, must still error here
    * instead of blocking on a keyboard read). `tty`/`readStdin` are injected so
    * tests exercise every branch without touching the real console or blocking
    * on real stdin.
    */
  private[cli] def readTask(
      task: Option[String],
      tty: Boolean,
      readStdin: () => String
  ): Either[String, String] =
    task match
      case Some(text) if text.trim.nonEmpty => Right(text.trim)
      case Some(_)                          => Left("task text can't be empty")
      case None =>
        if tty then
          Left(
            "no task given, and stdin is a terminal — " +
              "pass the task as an argument (--prompt=<text> if it starts " +
              "with '-'), or pipe it in"
          )
        else
          val piped = readStdin().trim
          if piped.isEmpty then
            Left(
              "no task given, and stdin was empty — pass the task as an " +
                "argument (--prompt=<text> if it starts with '-'), or pipe " +
                "non-empty input"
            )
          else Right(piped)
