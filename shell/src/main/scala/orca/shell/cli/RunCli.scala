package orca.shell.cli

import orca.RunTarget
import orca.shell.actions.{FlowResolution, RunAction}
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher}

import Cli.{actionFailure, complete, usageFailure, withTerminal}

/** `orca run`'s behavior (ADR 0021 §10): resolve the flow, read the task
  * (argument or piped stdin), then either the forced run ([[RunAction.run]]) or
  * the pin-honouring one ([[FlowLauncher.runHonoringPin]]), propagating the
  * flow child's raw exit code.
  */
private[cli] object RunCli:

  /** `target` arrives unvalidated — a `Left` is the refusal
    * [[orca.RunTarget.from]] returned for a contradictory `--worktree` pair. It
    * is refused first, before anything is resolved or spawned, saving a
    * `scala-cli` start and its dependency resolution; the flow child refuses
    * the same argv on the same shared decision and stays the authority, this
    * only makes the answer immediate. Below the refusal only the validated
    * target exists, so no launch path can be handed a pair orca refuses.
    */
  def run(
      flowRef: String,
      task: Option[String],
      verbose: Boolean,
      target: Either[String, RunTarget],
      honorPin: Boolean,
      workDir: os.Path,
      tty: Boolean
  ): Int =
    complete:
      for
        runTarget <- target.left.map(usageFailure)
        resolved <- FlowResolution
          .resolve(flowRef, workDir)
          .left
          .map(actionFailure)
        taskText <- readTask(task, tty, readAllStdin).left.map(usageFailure)
      yield withTerminal: terminal =>
        val flags = FlowFlags(verbose, runTarget)
        val result =
          if honorPin then
            FlowLauncher.runHonoringPin(
              resolved.path,
              taskText,
              workDir,
              flags,
              terminal
            )
          else
            RunAction.run(
              resolved,
              taskText,
              RunAction.RunOptions(
                flags = flags,
                fallback = FallbackPolicy.Refuse("re-run with --honor-pin")
              ),
              workDir,
              terminal
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
              "pass the task as an argument, or pipe it in"
          )
        else
          val piped = readStdin().trim
          if piped.isEmpty then
            Left(
              "no task given, and stdin was empty — pass the task as an " +
                "argument, or pipe non-empty input"
            )
          else Right(piped)
