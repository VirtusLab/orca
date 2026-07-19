package orca.shell.cli

import mainargs.Flag
import org.jline.terminal.Terminal
import orca.agents.BackendTag
import orca.settings.{AgentSpec, GlobalSettings}
import orca.shell.actions.{AuthorAction, AuthorOutcome, AuthorParams}
import orca.shell.actions.FlowResolution
import orca.shell.create.{CreateTarget, CreateTier, FlowAuthoring}
import orca.shell.ui.{ShellOutput, ShellUi}

import Cli.{actionFailure, complete, requireNonBlank, requireTty, usageFailure}

/** `orca create` and `orca fork`'s behavior (ADR 0021 §10/§9): the shared
  * author pipeline both drive — tty-gate, non-blank guard, harness/tier/yolo
  * resolution, filename validation, target preparation, then the authoring
  * exec. Create and fork differ only in what they resolve up front (nothing vs.
  * the source flow), the default filename, and which [[AuthorAction]] method
  * launches the session.
  */
private[cli] object AuthorCli:

  def create(
      name: Option[String],
      goal: String,
      harness: Option[String],
      global: Flag,
      yolo: Flag,
      noYolo: Flag,
      tty: Boolean,
      workDir: os.Path
  ): Int =
    runAuthor(
      command = "create",
      tty = tty,
      blankArg = "goal",
      blankValue = goal,
      name = name,
      global = global,
      yolo = yolo,
      noYolo = noYolo,
      harness = harness,
      workDir = workDir,
      resolveSource = Right(()),
      defaultFileName = _ => FlowAuthoring.suggestFilenameForGoal(goal),
      launch = (_, params, ui, terminal) =>
        AuthorAction.create(goal, params, workDir, ui, terminal)
    )

  def fork(
      source: String,
      name: Option[String],
      changes: String,
      harness: Option[String],
      global: Flag,
      yolo: Flag,
      noYolo: Flag,
      tty: Boolean,
      workDir: os.Path
  ): Int =
    runAuthor(
      command = "fork",
      tty = tty,
      blankArg = "changes",
      blankValue = changes,
      name = name,
      global = global,
      yolo = yolo,
      noYolo = noYolo,
      harness = harness,
      workDir = workDir,
      resolveSource =
        FlowResolution.resolve(source, workDir).left.map(actionFailure),
      defaultFileName = src => FlowAuthoring.forkFilenameDefault(src.name),
      launch = (src, params, ui, terminal) =>
        AuthorAction.fork(src, changes, params, workDir, ui, terminal)
    )

  /** The pipeline `create` and `fork` share (ADR 0021 §9). `resolveSource`
    * yields the fork's source flow (or `()` for create) before the flags are
    * parsed, matching the original order in which fork resolved its source
    * ahead of the harness/tier flags; `defaultFileName` derives the filename
    * default from it lazily, only when no `name` was given; `launch` hands the
    * prepared target to the matching authoring action.
    */
  private def runAuthor[S](
      command: String,
      tty: Boolean,
      blankArg: String,
      blankValue: String,
      name: Option[String],
      global: Flag,
      yolo: Flag,
      noYolo: Flag,
      harness: Option[String],
      workDir: os.Path,
      resolveSource: => Either[CliFailure, S],
      defaultFileName: S => String,
      launch: (S, AuthorParams, ShellUi, Terminal) => AuthorOutcome
  ): Int =
    val globalFlows = GlobalSettings.defaultFlows
    complete:
      for
        _ <- requireTty(command, tty).left.map(usageFailure)
        _ <- requireNonBlank(blankArg, blankValue).left.map(usageFailure)
        source <- resolveSource
        resolved <- authorParams(global, yolo, noYolo, harness).left
          .map(usageFailure)
        (tier, backend, yoloValue) = resolved
        fileName = name.getOrElse(defaultFileName(source))
        _ <- validateFileName(fileName).left.map(usageFailure)
        target <- safePrepareTarget(tier, fileName, workDir, globalFlows).left
          .map(actionFailure)
      yield launchAuthoring(
        target,
        AuthorParams(tier, target, backend, yoloValue),
        source,
        launch
      )

  private def launchAuthoring[S](
      target: CreateTarget,
      params: AuthorParams,
      source: S,
      launch: (S, AuthorParams, ShellUi, Terminal) => AuthorOutcome
  ): Int =
    ShellOutput.info(s"target flow: ${target.flowPath}")
    Cli.withTerminal: terminal =>
      val ui = ShellUi.make(terminal)
      exitCodeFor(launch(source, params, ui, terminal))

  /** `name`/fork's filename argument is documented as a bare filename, not a
    * path — rejects one containing a path separator (`../escape.sc`,
    * `sub/dir.sc`) with a clean usage error up front, before it ever reaches
    * [[FlowAuthoring.prepareTarget]]'s path arithmetic (which, for a name with
    * enough `..`s, os-lib can reject by throwing a raw `PathError` instead of
    * returning one).
    */
  private[cli] def validateFileName(fileName: String): Either[String, Unit] =
    Either.cond(
      !fileName.contains("/") && !fileName.contains("\\"),
      (),
      s"'$fileName' isn't a valid flow filename — path separators aren't allowed"
    )

  /** [[FlowAuthoring.prepareTarget]], with any exception os-lib's path
    * arithmetic throws for a filename that survived [[validateFileName]] but
    * still drives it outside the filesystem root (e.g.
    * `os.PathError.AbsolutePathOutsideRoot` — every `os.PathError` variant is a
    * plain `IllegalArgumentException`, there's no shared marker type to catch)
    * converted to a clean `Left` instead of propagating as an uncaught
    * exception.
    */
  private[cli] def safePrepareTarget(
      tier: CreateTier,
      fileName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, CreateTarget] =
    try FlowAuthoring.prepareTarget(tier, fileName, workDir, globalFlows)
    catch
      case _: IllegalArgumentException =>
        Left(s"'$fileName' isn't a valid flow filename")

  /** Shared `create`/`fork` flag resolution: the tier, the harness (parsed
    * `--harness`, or the configured coding agent), and yolo (on by default,
    * `--no-yolo` to disable — mutually exclusive with an explicit `--yolo`).
    */
  private[cli] def authorParams(
      global: Flag,
      yolo: Flag,
      noYolo: Flag,
      harness: Option[String]
  ): Either[String, (CreateTier, BackendTag, Boolean)] =
    if yolo.value && noYolo.value then
      Left("--yolo and --no-yolo are mutually exclusive")
    else
      resolveHarness(harness).map: backend =>
        val tier =
          if global.value then CreateTier.Global else CreateTier.Project
        (tier, backend, !noYolo.value)

  private[cli] def resolveHarness(
      raw: Option[String]
  ): Either[String, BackendTag] =
    raw match
      case None =>
        Right(FlowAuthoring.configuredCodingAgent(GlobalSettings.default))
      case Some(name) =>
        AgentSpec.harnessNames
          .get(name)
          .toRight(
            s"unknown harness '$name' — valid: " +
              AgentSpec.harnessNames.keys.toList.sorted.mkString(", ")
          )

  private[cli] def exitCodeFor(outcome: AuthorOutcome): Int = outcome match
    case AuthorOutcome.Launched(exit) => exit
    case AuthorOutcome.NotLaunched    => ExitCodes.ActionFailed
