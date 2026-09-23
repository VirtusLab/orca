package orca.shell.run

import org.jline.terminal.Terminal
import orca.{FlowSourceProperty, OrcaArgs}
import orca.progress.FlowSource
import orca.shell.{ShellEnv, ShellVersion}
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

  /** [[runAnnounced]]'s exact shape — a type alias so the actions that launch a
    * flow can take it as an injectable parameter, defaulting to the real thing,
    * the same way `FlowAuthoring.suggestFilename`'s `runner` stands in for a
    * real subprocess in tests.
    */
  private[shell] type FlowLaunch =
    (
        FallbackPolicy,
        LaunchedFlow,
        OrcaArgs,
        os.Path,
        Terminal
    ) => LaunchResult

  private val orgAndArtifact = "org.virtuslab::orca"

  /** scala-cli's own logging flags, on every spawn.
    *
    * `--quiet` silences coursier's per-artifact fetch log — on a cold cache a
    * ~900-line wall of `Downloading`/`Downloaded`/`Failed to download` before
    * the flow prints anything. But it also drops scala-cli's verbosity to -1,
    * where scala-cli prints no build exception at all: a flow whose
    * dependencies fail to resolve would exit nonzero in silence. `--verbose`
    * puts verbosity back to 0, leaving the fetch log off. Verified against
    * scala-cli 1.15.0; the `orca` shim passes the same pair (ADR 0021).
    */
  private val loggingArgs = Seq("--quiet", "--verbose")

  /** `--dep org.virtuslab::orca:<v>`, or nothing when `orcaVersion` is `None`
    * (dev build, or an already-declined fallback).
    */
  private def depArgs(orcaVersion: Option[String]): Seq[String] =
    orcaVersion
      .map(v => Seq("--dep", s"$orgAndArtifact:$v"))
      .getOrElse(Seq.empty)

  /** Tells the flow child its [[FlowSource]] ([[FlowSourceProperty]]). On the
    * compile probe too, since scala-cli rebuilds when a `--java-prop` value
    * changes.
    */
  private def flowSourceArgs(source: FlowSource): Seq[String] =
    Seq("--java-prop", FlowSourceProperty.assignment(source))

  /** `scala-cli run <flow> --quiet --verbose [--dep ...] --java-prop ...
    * --workspace <dir> -- <args>`. The `--verbose` before `--` is scala-cli's
    * own ([[loggingArgs]]); everything after `--` is the flow's own
    * ([[orca.OrcaArgs.toArgv]]). `--workspace` relocates scala-cli's own
    * `.scala-build`/`.bsp` build metadata to `workspaceDir`
    * ([[resolveWorkspaceDir]]) instead of next to `flow` — load-bearing for a
    * Project-tier flow, whose script lives inside the user's own repo
    * (`<repo>/.orca/flows/<name>.sc`), same pollution class the `orca` shim's
    * own `--workspace` fixes (ADR 0021 §1 amendment).
    *
    * Requires `args.userPrompt` to be non-blank — callers refuse a blank task
    * first, so an empty task here means a caller bug, not a user error to
    * report.
    */
  def argv(
      flow: LaunchedFlow,
      orcaVersion: Option[String],
      args: OrcaArgs,
      workspaceDir: os.Path
  ): Seq[String] =
    require(
      args.userPrompt.trim.nonEmpty,
      "task text must be non-blank — callers refuse a blank task first"
    )
    Seq("scala-cli", "run", flow.path.toString) ++
      loggingArgs ++
      depArgs(orcaVersion) ++
      flowSourceArgs(flow.source) ++
      Seq("--workspace", workspaceDir.toString, "--") ++
      args.toArgv

  /** The compile probe's argv — same `--workspace` treatment as [[argv]], and
    * for the same reason: without it, the probe (run whenever the forced
    * version fails) would write its own `.scala-build` next to `flow` too.
    */
  private[run] def compileArgv(
      flow: LaunchedFlow,
      orcaVersion: Option[String],
      workspaceDir: os.Path
  ): Seq[String] =
    Seq(
      "scala-cli",
      "compile",
      flow.path.toString
    ) ++ depArgs(orcaVersion) ++ flowSourceArgs(flow.source) ++
      Seq("--workspace", workspaceDir.toString)

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

  /** `<cacheHome>/orca/shell/workspace` (created with `mkdir -p` before every
    * spawn) — [[argv]]/[[compileArgv]]'s `--workspace` target.
    */
  private def resolveWorkspaceDir()(using env: ShellEnv): os.Path =
    val dir = env.cacheHome / "orca" / "shell" / "workspace"
    os.makeDir.all(dir)
    dir

  private def spawnInherited(argv: Seq[String], workDir: os.Path): Int =
    os.proc(argv)
      .call(
        cwd = workDir,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = false
      )
      .exitCode

  private def fallbackQuestion(shellVersion: String): String =
    s"This flow pins an orca version incompatible with the shell ($shellVersion) — " +
      "sessions from a pin-honoring run can't be continued. Run anyway?"

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
    *
    * The child is silent while it resolves ([[loggingArgs]]) and the launcher
    * can't see when that starts or ends, so the notice is unconditional: a warm
    * cache gets one extra line, a cold one gets the only sign the run isn't
    * hung.
    */
  private[run] def announced(startLabel: String, flowName: String)(
      spawn: => LaunchResult
  ): LaunchResult =
    println()
    ShellOutput.section(startLabel)
    ShellOutput.info("resolving dependencies…")
    val result = spawn
    ShellOutput.section(s"flow $flowName ${outcomeSuffix(result)}")
    println()
    result

  /** Top-level flow run for the menu and `orca run`: the announced bracket
    * around [[run]], executed as a tty-inherited child under
    * [[ChildTerminal.withChild]] (ADR 0021 §2). Owns the section markers and
    * the terminal bracket so callers hand off a single call.
    */
  private[shell] def runAnnounced(
      fallback: FallbackPolicy,
      flow: LaunchedFlow,
      args: OrcaArgs,
      workDir: os.Path,
      terminal: Terminal
  )(using ShellEnv): LaunchResult =
    announced(s"starting flow ${flow.fileName}", flow.fileName)(
      ChildTerminal.withChild(terminal)(
        run(fallback, flow, args, workDir)
      )
    )

  /** `--honor-pin`'s direct pin-honouring run (ADR 0021 §2/§10): [[run]] has no
    * "skip the forced version from the start" path — its pin-honouring re-run
    * is only offered as a fallback after a forced failure — so this spawns
    * [[argv]]'s pin-honouring argv (no `--dep`) itself, under the same
    * [[ChildTerminal.withChild]] bracket and announced markers the forced run
    * uses. No compile probe or fallback: the user has already opted into the
    * flow's own pin.
    */
  private[shell] def runHonoringPin(
      flow: LaunchedFlow,
      args: OrcaArgs,
      workDir: os.Path,
      terminal: Terminal
  )(using ShellEnv): LaunchResult =
    announced(
      s"starting flow ${flow.fileName} (honoring pin)",
      flow.fileName
    )(
      ChildTerminal.withChild(terminal)(
        toLaunchResult(
          spawnInherited(
            argv(flow, None, args, resolveWorkspaceDir()),
            workDir
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
      flow: LaunchedFlow,
      args: OrcaArgs,
      workDir: os.Path
  )(using ShellEnv): LaunchResult =
    val shellVersion = ShellVersion.value
    val forcedVersion =
      if ShellVersion.isRelease(shellVersion) then Some(shellVersion) else None
    val workspaceDir = resolveWorkspaceDir()
    val forcedExit = spawnInherited(
      argv(flow, forcedVersion, args, workspaceDir),
      workDir
    )
    val compileProbe = () =>
      QuietProc
        .call(compileArgv(flow, forcedVersion, workspaceDir), cwd = workDir)
        .exitCode
    resolveNextAction(forcedExit, forcedVersion.isDefined, compileProbe) match
      case NextAction.Succeed             => LaunchResult.Ok
      case NextAction.ReportFailure(exit) => LaunchResult.Failed(exit)
      case NextAction.CancelledBySignal   => LaunchResult.Cancelled
      case NextAction.OfferFallback =>
        fallback match
          case FallbackPolicy.Ask(ui) =>
            ui.confirm(fallbackQuestion(shellVersion), default = true) match
              case UiOutcome.Selected(true) =>
                announced(
                  s"pin-honoring re-run of ${flow.fileName}",
                  flow.fileName
                )(
                  toLaunchResult(
                    spawnInherited(
                      argv(flow, None, args, workspaceDir),
                      workDir
                    )
                  )
                )
              case UiOutcome.Selected(false) | UiOutcome.Cancelled =>
                LaunchResult.Cancelled
          case FallbackPolicy.Refuse(hint) =>
            ShellOutput.error(s"${fallbackQuestion(shellVersion)} $hint")
            LaunchResult.Failed(forcedExit)
