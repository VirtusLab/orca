package orca.shell.cli

import orca.shell.actions.{FlowResolution, RunAction}
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchResult}

/** `orca run`'s behavior (ADR 0021 §10): resolve the flow, read the task
  * (argument or piped stdin), then either the forced run ([[RunAction.run]]) or
  * the pin-honouring one ([[FlowLauncher.runHonoringPin]]), propagating the
  * flow child's raw exit code.
  */
private[cli] object RunCli:

  def run(
      flowRef: String,
      task: Option[String],
      verbose: Boolean,
      honorPin: Boolean,
      workDir: os.Path,
      tty: Boolean
  ): Int =
    FlowResolution.resolve(flowRef, workDir) match
      case Left(message) =>
        Cli.fail(message)
        ExitCodes.ActionFailed
      case Right(resolved) =>
        readTask(task, tty, readAllStdin) match
          case Left(message) =>
            Cli.fail(message)
            ExitCodes.UsageError
          case Right(taskText) =>
            Cli.withTerminal: terminal =>
              val result =
                if honorPin then
                  FlowLauncher.runHonoringPin(
                    resolved.path,
                    taskText,
                    workDir,
                    verbose,
                    terminal
                  )
                else
                  RunAction.run(
                    resolved,
                    taskText,
                    RunAction.RunOptions(
                      verbose = verbose,
                      fallback =
                        FallbackPolicy.Refuse("re-run with --honor-pin")
                    ),
                    workDir,
                    terminal
                  )
              // propagates the flow child's raw exit code (LaunchResult.Failed's
              // exit, via exitCodeFor) — run mirrors a wrapped subprocess's
              // status rather than the flat 0/1/2 usage-error convention.
              exitCodeFor(result)

  private def readAllStdin(): String =
    String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)

  /** `task` when non-blank; `readStdin()` read to EOF when `task` is omitted
    * and `tty` is false (`enables generate-prompt | orca run fix.sc`); a usage
    * error otherwise — never blocks waiting on a terminal that has no task
    * coming. `tty`/`readStdin` are injected (production: `Cli.isTty`/
    * [[readAllStdin]]) so tests exercise every branch without touching the real
    * console or blocking on real stdin.
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
              "pass the task as an argument, or pipe it in"
          )
        else
          val piped = readStdin().trim
          if piped.isEmpty then Left("no task given, and stdin was empty")
          else Right(piped)

  private[cli] def exitCodeFor(result: LaunchResult): Int = result match
    case LaunchResult.Ok           => ExitCodes.Ok
    case LaunchResult.Failed(exit) => exit
    case LaunchResult.Cancelled    => ExitCodes.SignalKilled
