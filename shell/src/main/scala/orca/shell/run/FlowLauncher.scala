package orca.shell.run

import orca.shell.ShellVersion
import orca.shell.ui.{ShellUi, UiOutcome}
import orca.subprocess.QuietProc

/** Outcome of [[FlowLauncher.run]]. */
enum LaunchResult:
  case Ok
  case Failed(exit: Int)
  case Cancelled

/** Runs a selected flow as a `scala-cli run` child inheriting the shell's
  * terminal (ADR 0021 §2). By default the shell forces its own orca version
  * via `--dep`, overriding the flow's own `//> using dep` pin, so the
  * run-manifest writer is guaranteed present; on a version-incompatible flow
  * (forced compile fails) it falls back to a pin-honouring re-run at the
  * user's confirmation.
  */
object FlowLauncher:

  private val orgAndArtifact = "org.virtuslab::orca"

  /** `--dep org.virtuslab::orca:<v>`, or nothing when `orcaVersion` is
    * `None` (dev build, or an already-declined fallback).
    */
  private def depArgs(orcaVersion: Option[String]): Seq[String] =
    orcaVersion.map(v => Seq("--dep", s"$orgAndArtifact:$v")).getOrElse(Seq.empty)

  /** `scala-cli run <flow> [--dep ...] -- <task> [--verbose]`. `--verbose`
    * is a flow-script argument (parsed by the flow's own `OrcaArgs`, whose
    * flag is spelled `--verbose`), so it lands after `--` alongside the
    * task text, not before it.
    */
  def argv(flow: os.Path, orcaVersion: Option[String], task: String, verbose: Boolean): Seq[String] =
    val verboseArgs = if verbose then Seq("--verbose") else Seq.empty
    Seq("scala-cli", "run", flow.toString) ++ depArgs(orcaVersion) ++ Seq("--", task) ++ verboseArgs

  private def compileArgv(flow: os.Path, orcaVersion: Option[String]): Seq[String] =
    Seq("scala-cli", "compile", flow.toString) ++ depArgs(orcaVersion)

  /** What to do once the forced run has finished: `compileExit` is `None`
    * when no probe ran (there was nothing forced to blame — either the run
    * succeeded, or it was already pin-honouring).
    */
  enum NextAction:
    case Succeed
    case ReportFailure(exit: Int)
    case OfferFallback

  /** Pure decision at the core of the fallback dance (ADR 0021 §2): a
    * nonzero forced exit is only a genuine flow failure if `scala-cli
    * compile` (run with the same forced `--dep`) also fails — otherwise the
    * flow itself is broken, not the version override.
    */
  def decideNextAction(forcedExit: Int, compileExit: Option[Int]): NextAction =
    if forcedExit == 0 then NextAction.Succeed
    else
      compileExit match
        case Some(0) => NextAction.ReportFailure(forcedExit)
        case Some(_) => NextAction.OfferFallback
        case None    => NextAction.ReportFailure(forcedExit)

  private def spawnInherited(argv: Seq[String], workDir: os.Path): Int =
    os.proc(argv)
      .call(cwd = workDir, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit, check = false)
      .exitCode

  private def fallbackQuestion(shellVersion: String): String =
    s"This flow pins an orca version incompatible with the shell ($shellVersion) — " +
      "sessions from a pin-honouring run can't be continued. Run anyway?"

  /** Runs `flow` forced to the shell's own orca version (skipped — i.e. the
    * forced and pin-honouring runs coincide — when the running shell is a
    * dev build, never an unpublishable version to force). On a forced
    * failure that a compile probe also reproduces, offers a pin-honouring
    * re-run via `ui.confirm`, with the notice that its sessions won't be
    * continuable. The verbose toggle is wired by task 4.2 alongside the
    * menu item; runs here are never verbose.
    */
  def run(ui: ShellUi, flow: os.Path, task: String, workDir: os.Path): LaunchResult =
    val shellVersion = ShellVersion.value
    val forcedVersion = if ShellVersion.isRelease(shellVersion) then Some(shellVersion) else None
    val forcedExit = spawnInherited(argv(flow, forcedVersion, task, verbose = false), workDir)
    val compileExit =
      if forcedExit != 0 && forcedVersion.isDefined then
        Some(QuietProc.call(compileArgv(flow, forcedVersion), cwd = workDir).exitCode)
      else None
    decideNextAction(forcedExit, compileExit) match
      case NextAction.Succeed             => LaunchResult.Ok
      case NextAction.ReportFailure(exit) => LaunchResult.Failed(exit)
      case NextAction.OfferFallback =>
        ui.confirm(fallbackQuestion(shellVersion), default = true) match
          case UiOutcome.Selected(true) =>
            val fallbackExit = spawnInherited(argv(flow, None, task, verbose = false), workDir)
            if fallbackExit == 0 then LaunchResult.Ok else LaunchResult.Failed(fallbackExit)
          case UiOutcome.Selected(false) | UiOutcome.Cancelled => LaunchResult.Cancelled
