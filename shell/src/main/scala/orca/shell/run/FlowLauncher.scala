package orca.shell.run

import org.jline.terminal.Terminal
import orca.shell.ShellVersion
import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}
import orca.subprocess.QuietProc

/** Outcome of [[FlowLauncher.run]]. */
private[shell] enum LaunchResult:
  case Ok
  case Failed(exit: Int)
  case Cancelled

/** Who decides whether to fall back to a pin-honouring re-run when the forced
  * version fails to compile (ADR 0021 §2, [[FlowLauncher.run]]'s
  * [[FlowLauncher.NextAction.OfferFallback]] branch): the interactive menu asks
  * via a [[ShellUi]] confirm; a non-interactive caller refuses outright with a
  * hint instead of ever prompting.
  */
private[shell] enum FallbackPolicy:
  case Ask(ui: ShellUi)
  case Refuse(hint: String)

/** Runs a selected flow as a `scala-cli run` child inheriting the shell's
  * terminal (ADR 0021 §2). By default the shell forces its own orca version via
  * `--dep`, overriding the flow's own `//> using dep` pin, so the run-manifest
  * writer is guaranteed present; on a version-incompatible flow (forced compile
  * fails) it falls back to a pin-honouring re-run at the user's confirmation.
  */
private[shell] object FlowLauncher:

  private val orgAndArtifact = "org.virtuslab::orca"

  /** `--dep org.virtuslab::orca:<v>`, or nothing when `orcaVersion` is `None`
    * (dev build, or an already-declined fallback).
    */
  private def depArgs(orcaVersion: Option[String]): Seq[String] =
    orcaVersion
      .map(v => Seq("--dep", s"$orgAndArtifact:$v"))
      .getOrElse(Seq.empty)

  /** `scala-cli run <flow> [--dep ...] -- <task> [--verbose]`. `--verbose` is a
    * flow-script argument (parsed by the flow's own `OrcaArgs`, whose flag is
    * spelled `--verbose`), so it lands after `--` alongside the task text, not
    * before it.
    *
    * Requires `task` to be non-blank — `Main.promptTask` re-prompts on blank
    * input before this is ever called, so an empty task here means a caller
    * bug, not a user error to report.
    */
  def argv(
      flow: os.Path,
      orcaVersion: Option[String],
      task: String,
      verbose: Boolean
  ): Seq[String] =
    require(
      task.trim.nonEmpty,
      "task text must be non-blank — Main.promptTask re-prompts before calling"
    )
    val verboseArgs = if verbose then Seq("--verbose") else Seq.empty
    Seq("scala-cli", "run", flow.toString) ++ depArgs(orcaVersion) ++ Seq(
      "--",
      task
    ) ++ verboseArgs

  private def compileArgv(
      flow: os.Path,
      orcaVersion: Option[String]
  ): Seq[String] =
    Seq("scala-cli", "compile", flow.toString) ++ depArgs(orcaVersion)

  /** What to do once the forced run has finished: `compileExit` is `None` when
    * no probe ran (there was nothing forced to blame — either the run
    * succeeded, or it was already pin-honouring).
    */
  enum NextAction:
    case Succeed
    case ReportFailure(exit: Int)
    case OfferFallback
    case CancelledBySignal

  /** Pure decision at the core of the fallback dance (ADR 0021 §2): a nonzero
    * forced exit is a genuine flow failure only when `scala-cli compile` (run
    * with the same forced `--dep`) also succeeds — that proves the forced
    * version compiles fine, so the flow itself is what's broken. When the
    * compile probe also fails, the forced version is to blame instead, and a
    * pin-honouring fallback is offered.
    */
  def decideNextAction(forcedExit: Int, compileExit: Option[Int]): NextAction =
    if forcedExit == 0 then NextAction.Succeed
    else
      compileExit match
        case Some(0) => NextAction.ReportFailure(forcedExit)
        case Some(_) => NextAction.OfferFallback
        case None    => NextAction.ReportFailure(forcedExit)

  /** A run conventionally killed by a signal (128 + signal number, e.g. 130 for
    * SIGINT, 143 for SIGTERM — `man 7 signal`'s exit-status convention). A
    * script deliberately exiting >= 128 is indistinguishable and gets
    * classified as cancelled too — accepted imprecision. For a forced run
    * there's nothing here to blame on the version override, so no compile probe
    * is warranted.
    */
  private def isSignalExit(exit: Int): Boolean = exit >= 128

  /** Decides the next action for a forced run, calling `compileProbe` only when
    * a probe is actually warranted (a nonzero, non-signal exit, with a forced
    * version to blame) — split out of [[run]] so the no-probe-on-signal-exit
    * behaviour is unit-testable with a recording thunk instead of a real
    * `scala-cli compile` subprocess.
    */
  private[run] def resolveNextAction(
      forcedExit: Int,
      forcedVersionDefined: Boolean,
      compileProbe: () => Int
  ): NextAction =
    if isSignalExit(forcedExit) then NextAction.CancelledBySignal
    else
      val compileExit =
        if forcedExit != 0 && forcedVersionDefined then Some(compileProbe())
        else None
      decideNextAction(forcedExit, compileExit)

  /** Signal-range exits map to Cancelled on every spawn path — the fallback
    * re-run is just as interruptible as the forced one. Every raw-exit spawn
    * path in this object ([[run]]'s fallback, [[runHonoringPin]]) maps through
    * here, so the classification stays in one place.
    */
  private[run] def toLaunchResult(exit: Int): LaunchResult =
    if exit == 0 then LaunchResult.Ok
    else if isSignalExit(exit) then LaunchResult.Cancelled
    else LaunchResult.Failed(exit)

  /** `ORCA_FLOW_NAME`, read by `runner`'s `flow()` (`orca.flow.scala`) to stamp
    * the run manifest's `flow` field — the flow script's own filename, per the
    * manifest schema (`RunManifest.flow`'s scaladoc examples), unavailable from
    * inside the running script itself.
    */
  private[run] def childEnv(flow: os.Path): Map[String, String] =
    Map("ORCA_FLOW_NAME" -> flow.last)

  /** `env` is added onto the inherited environment (os-lib's `ProcessBuilder`
    * starts from the parent's own env), not a replacement of it.
    */
  private def spawnInherited(
      argv: Seq[String],
      workDir: os.Path,
      env: Map[String, String]
  ): Int =
    os.proc(argv)
      .call(
        cwd = workDir,
        env = env,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = false
      )
      .exitCode

  private def fallbackQuestion(shellVersion: String): String =
    s"This flow pins an orca version incompatible with the shell ($shellVersion) — " +
      "sessions from a pin-honouring run can't be continued. Run anyway?"

  /** The flow-end line's outcome suffix: `finished (exit N)` for a completed
    * run, `finished (cancelled)` for a signal-killed one.
    */
  private[run] def outcomeSuffix(result: LaunchResult): String = result match
    case LaunchResult.Ok           => "finished (exit 0)"
    case LaunchResult.Failed(exit) => s"finished (exit $exit)"
    case LaunchResult.Cancelled    => "finished (cancelled)"

  /** The shared `println / section(start) / … / section(end) / println` bracket
    * every foreground flow spawn prints around itself: the top-level forced run
    * ([[runAnnounced]]), the `--honor-pin` run ([[runHonoringPin]]), and
    * [[run]]'s own pin-honouring fallback re-run. `spawn` produces the
    * [[LaunchResult]] whose [[outcomeSuffix]] closes the bracket.
    */
  private[run] def announced(startLabel: String, flowName: String)(
      spawn: => LaunchResult
  ): LaunchResult =
    println()
    ShellOutput.section(startLabel)
    val result = spawn
    ShellOutput.section(s"flow $flowName ${outcomeSuffix(result)}")
    println()
    result

  /** Top-level flow run for [[orca.shell.actions.RunAction]]: the announced
    * bracket around [[run]], executed as a tty-inherited child under
    * [[ChildTerminal.withChild]] (ADR 0021 §2). Owns the section markers and
    * the terminal bracket so callers hand off a single call.
    */
  private[shell] def runAnnounced(
      fallback: FallbackPolicy,
      flow: os.Path,
      task: String,
      workDir: os.Path,
      verbose: Boolean,
      terminal: Terminal
  ): LaunchResult =
    announced(s"starting flow ${flow.last}", flow.last)(
      ChildTerminal.withChild(terminal)(
        run(fallback, flow, task, workDir, verbose)
      )
    )

  /** `--honor-pin`'s direct pin-honouring run (ADR 0021 §2/§10): [[run]] has no
    * "skip the forced version from the start" path — its pin-honouring re-run
    * is only offered as a fallback after a forced failure — so this spawns
    * [[argv]]'s pin-honouring argv (no `--dep`) itself, under the same
    * [[ChildTerminal.withChild]] bracket, [[childEnv]] stamp, and announced
    * markers the forced run uses. No compile probe or fallback: the user has
    * already opted into the flow's own pin.
    */
  private[shell] def runHonoringPin(
      flow: os.Path,
      task: String,
      workDir: os.Path,
      verbose: Boolean,
      terminal: Terminal
  ): LaunchResult =
    announced(s"starting flow ${flow.last} (honoring pin)", flow.last)(
      ChildTerminal.withChild(terminal)(
        toLaunchResult(
          spawnInherited(
            argv(flow, None, task, verbose),
            workDir,
            childEnv(flow)
          )
        )
      )
    )

  /** Runs `flow` forced to the shell's own orca version (skipped — i.e. the
    * forced and pin-honouring runs coincide — when the running shell is a dev
    * build, never an unpublishable version to force). On a forced failure that
    * a compile probe also reproduces, `fallback` decides what happens next:
    * [[FallbackPolicy.Ask]] offers a pin-honouring re-run via `ui.confirm`,
    * with the notice that its sessions won't be continuable;
    * [[FallbackPolicy.Refuse]] reports the forced failure directly, with its
    * hint appended, never prompting. A forced run killed by a signal (Ctrl-C's
    * SIGINT, or a SIGTERM) is reported as [[LaunchResult.Cancelled]] directly,
    * without a compile probe or fallback offer either way — there's nothing to
    * blame on the version override.
    */
  def run(
      fallback: FallbackPolicy,
      flow: os.Path,
      task: String,
      workDir: os.Path,
      verbose: Boolean
  ): LaunchResult =
    val shellVersion = ShellVersion.value
    val forcedVersion =
      if ShellVersion.isRelease(shellVersion) then Some(shellVersion) else None
    val forcedExit = spawnInherited(
      argv(flow, forcedVersion, task, verbose),
      workDir,
      childEnv(flow)
    )
    val compileProbe = () =>
      QuietProc.call(compileArgv(flow, forcedVersion), cwd = workDir).exitCode
    resolveNextAction(forcedExit, forcedVersion.isDefined, compileProbe) match
      case NextAction.Succeed             => LaunchResult.Ok
      case NextAction.ReportFailure(exit) => LaunchResult.Failed(exit)
      case NextAction.CancelledBySignal   => LaunchResult.Cancelled
      case NextAction.OfferFallback =>
        fallback match
          case FallbackPolicy.Ask(ui) =>
            ui.confirm(fallbackQuestion(shellVersion), default = true) match
              case UiOutcome.Selected(true) =>
                announced(s"pin-honoring re-run of ${flow.last}", flow.last)(
                  toLaunchResult(
                    spawnInherited(
                      argv(flow, None, task, verbose),
                      workDir,
                      childEnv(flow)
                    )
                  )
                )
              case UiOutcome.Selected(false) | UiOutcome.Cancelled =>
                LaunchResult.Cancelled
          case FallbackPolicy.Refuse(hint) =>
            ShellOutput.error(s"${fallbackQuestion(shellVersion)} $hint")
            LaunchResult.Failed(forcedExit)
