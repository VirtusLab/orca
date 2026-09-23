package orca.shell.run

import org.jline.terminal.Terminal
import orca.{FlowSourceProperty, OrcaArgs}
import orca.progress.FlowSource
import orca.shell.{OrcaBuild, ShellEnv}
import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}
import orca.subprocess.QuietProc

/** Outcome of [[FlowLauncher.runAnnounced]]. */
private[shell] enum LaunchResult:
  case Ok
  case Failed(exit: Int)
  case Cancelled

/** Which orca a launched flow runs on (ADR 0021 §2). */
private[shell] enum PinPolicy:
  /** The shell's own [[OrcaBuild]], replacing the flow's pin, so the run
    * manifest writer is guaranteed present; `onIncompatible` decides what
    * happens when the flow doesn't compile against it.
    */
  case Force(onIncompatible: FallbackPolicy)

  /** The flow's own `//> using dep` pin (`orca run --honor-pin`). */
  case Honor

/** Who decides whether to fall back to a pin-honouring re-run when the forced
  * version fails to compile (ADR 0021 §2, [[FlowLauncher.runAnnounced]]'s
  * [[FlowLauncher.NextAction.OfferFallback]] branch): the interactive menu asks
  * via a [[ShellUi]] confirm; a non-interactive caller refuses outright with a
  * hint instead of ever prompting.
  */
private[shell] enum FallbackPolicy:
  case Ask(ui: ShellUi)
  case Refuse(hint: String)

/** Runs a selected flow as a `scala-cli run` child inheriting the shell's
  * terminal (ADR 0021 §2), on the orca its [[PinPolicy]] picks.
  */
private[shell] object FlowLauncher:

  /** [[runAnnounced]]'s exact shape — a type alias so the actions that launch a
    * flow can take it as an injectable parameter, defaulting to the real thing,
    * the same way `FlowAuthoring.suggestFilename`'s `runner` stands in for a
    * real subprocess in tests.
    */
  private[shell] type FlowLaunch =
    (
        PinPolicy,
        LaunchedFlow,
        OrcaArgs,
        os.Path,
        Terminal
    ) => LaunchResult

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

  /** `forced`'s [[OrcaBuild.forceArgs]], or nothing for a pin-honouring run.
    */
  private def depArgs(forced: Option[OrcaBuild]): Seq[String] =
    forced.toSeq.flatMap(_.forceArgs)

  /** Tells the flow child its [[FlowSource]] ([[FlowSourceProperty]]). On the
    * compile probe too, since scala-cli rebuilds when a `--java-prop` value
    * changes.
    */
  private def flowSourceArgs(source: FlowSource): Seq[String] =
    Seq("--java-prop", FlowSourceProperty.assignment(source))

  /** `scala-cli run <flow> --quiet --verbose [<forced build>] --java-prop ...
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
      forced: Option[OrcaBuild],
      args: OrcaArgs,
      workspaceDir: os.Path
  ): Seq[String] =
    require(
      args.userPrompt.trim.nonEmpty,
      "task text must be non-blank — callers refuse a blank task first"
    )
    Seq("scala-cli", "run", flow.path.toString) ++
      loggingArgs ++
      depArgs(forced) ++
      flowSourceArgs(flow.source) ++
      Seq("--workspace", workspaceDir.toString, "--") ++
      args.toArgv

  /** The compile probe's argv — same `--workspace` treatment as [[argv]], and
    * for the same reason: without it, the probe (run whenever the forced
    * version fails) would write its own `.scala-build` next to `flow` too.
    */
  private[run] def compileArgv(
      flow: LaunchedFlow,
      forced: OrcaBuild,
      workspaceDir: os.Path
  ): Seq[String] =
    Seq(
      "scala-cli",
      "compile",
      flow.path.toString
    ) ++ forced.forceArgs ++ flowSourceArgs(flow.source) ++
      Seq("--workspace", workspaceDir.toString)

  /** What to do once the forced run has finished. */
  enum NextAction:
    case Succeed
    case ReportFailure(exit: Int)
    case OfferFallback
    case CancelledBySignal

  /** A run conventionally killed by a signal (128 + signal number, e.g. 130 for
    * SIGINT, 143 for SIGTERM — `man 7 signal`'s exit-status convention). A
    * script deliberately exiting >= 128 is indistinguishable and gets
    * classified as cancelled too — accepted imprecision. For a forced run
    * there's nothing here to blame on the version override, so no compile probe
    * is warranted.
    */
  private def isSignalExit(exit: Int): Boolean = exit >= 128

  /** The decision at the core of the fallback dance (ADR 0021 §2): a nonzero,
    * non-signal forced exit is a genuine flow failure only when `compileProbe`
    * (`scala-cli compile` with the same forced build) succeeds — that proves
    * the forced build compiles fine, so the flow itself is what's broken. When
    * the probe also fails, the forced build is to blame instead, and a
    * pin-honouring fallback is offered. `compileProbe` runs only in that case;
    * tests pass a recording thunk instead of a real subprocess.
    */
  private[run] def resolveNextAction(
      forcedExit: Int,
      compileProbe: () => Int
  ): NextAction =
    if forcedExit == 0 then NextAction.Succeed
    else if isSignalExit(forcedExit) then NextAction.CancelledBySignal
    else if compileProbe() == 0 then NextAction.ReportFailure(forcedExit)
    else NextAction.OfferFallback

  /** Signal-range exits map to Cancelled on every spawn path — the fallback
    * re-run is just as interruptible as the forced one. Every raw-exit spawn
    * path in this object ([[runForced]]'s fallback, [[PinPolicy.Honor]]) maps
    * through here, so the classification stays in one place.
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

  /** A snapshot that isn't in the local Ivy repository fails the forced compile
    * the same way an incompatible flow does, so its question names both.
    */
  private def fallbackQuestion(build: OrcaBuild): String =
    val cause = build match
      case OrcaBuild.Release(v) =>
        s"This flow pins an orca version incompatible with the shell ($v)"
      case OrcaBuild.Snapshot(v) =>
        s"This flow pins an orca version incompatible with the shell ($v), " +
          "or that snapshot isn't published locally (sbt publishLocal)"
    s"$cause — sessions from a pin-honoring run can't be continued. Run anyway?"

  /** The flow-end line's outcome suffix: `finished (exit N)` for a completed
    * run, `finished (cancelled)` for a signal-killed one.
    */
  private[run] def outcomeSuffix(result: LaunchResult): String = result match
    case LaunchResult.Ok           => "finished (exit 0)"
    case LaunchResult.Failed(exit) => s"finished (exit $exit)"
    case LaunchResult.Cancelled    => "finished (cancelled)"

  /** The shared `println / section(start) / … / section(end) / println` bracket
    * every foreground flow spawn prints around itself: the top-level run
    * ([[runAnnounced]]) and [[runForced]]'s own pin-honouring fallback re-run.
    * `spawn` produces the [[LaunchResult]] whose [[outcomeSuffix]] closes the
    * bracket.
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
    * around the run `policy` picks, executed as a tty-inherited child under
    * [[ChildTerminal.withChild]] (ADR 0021 §2). Owns the section markers and
    * the terminal bracket so callers hand off a single call.
    */
  private[shell] def runAnnounced(
      policy: PinPolicy,
      flow: LaunchedFlow,
      args: OrcaArgs,
      workDir: os.Path,
      terminal: Terminal
  )(using ShellEnv): LaunchResult =
    val label = policy match
      case PinPolicy.Force(_) => s"starting flow ${flow.fileName}"
      case PinPolicy.Honor => s"starting flow ${flow.fileName} (honoring pin)"
    announced(label, flow.fileName)(
      ChildTerminal.withChild(terminal):
        policy match
          case PinPolicy.Force(onIncompatible) =>
            runForced(onIncompatible, flow, args, workDir)
          case PinPolicy.Honor =>
            toLaunchResult(
              spawnInherited(
                argv(flow, None, args, resolveWorkspaceDir()),
                workDir
              )
            )
    )

  /** Runs `flow` forced to [[OrcaBuild.current]]. On a forced failure that a
    * compile probe also reproduces, `fallback` decides what happens next:
    * [[FallbackPolicy.Ask]] offers a pin-honouring re-run via `ui.confirm`,
    * with the notice that its sessions won't be continuable;
    * [[FallbackPolicy.Refuse]] reports the forced failure directly, with its
    * hint appended, never prompting. A forced run killed by a signal (Ctrl-C's
    * SIGINT, or a SIGTERM) is reported as [[LaunchResult.Cancelled]] directly,
    * without a compile probe or fallback offer either way — there's nothing to
    * blame on the version override.
    */
  private def runForced(
      fallback: FallbackPolicy,
      flow: LaunchedFlow,
      args: OrcaArgs,
      workDir: os.Path
  )(using ShellEnv): LaunchResult =
    val build = OrcaBuild.current
    val workspaceDir = resolveWorkspaceDir()
    val forcedExit = spawnInherited(
      argv(flow, Some(build), args, workspaceDir),
      workDir
    )
    val compileProbe = () =>
      QuietProc
        .call(compileArgv(flow, build, workspaceDir), cwd = workDir)
        .exitCode
    resolveNextAction(forcedExit, compileProbe) match
      case NextAction.Succeed             => LaunchResult.Ok
      case NextAction.ReportFailure(exit) => LaunchResult.Failed(exit)
      case NextAction.CancelledBySignal   => LaunchResult.Cancelled
      case NextAction.OfferFallback =>
        fallback match
          case FallbackPolicy.Ask(ui) =>
            ui.confirm(fallbackQuestion(build), default = true) match
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
            ShellOutput.error(s"${fallbackQuestion(build)} $hint")
            LaunchResult.Failed(forcedExit)
