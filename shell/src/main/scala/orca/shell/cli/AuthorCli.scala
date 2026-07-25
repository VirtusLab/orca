package orca.shell.cli

import mainargs.Flag
import org.jline.terminal.Terminal
import orca.settings.GlobalSettings
import orca.shell.actions.{AuthorAction, AuthorParams}
import orca.shell.actions.FlowResolution
import orca.shell.create.{CreateTarget, CreateTier, FlowAuthoring}
import orca.shell.run.LaunchResult
import orca.shell.ui.{ShellOutput, ShellUi}

import Cli.{actionFailure, complete, requireNonBlank, requireTty, usageFailure}

/** `orca create` and `orca fork`'s behavior (ADR 0021 §10/§9): the shared
  * author pipeline both drive — tty-gate, non-blank guard, tier resolution,
  * target resolution, then the sandboxed authoring flow launch. The configured
  * role agents (and their model pins) do the writing automatically, and the run
  * happens in a throwaway sandbox — never the caller's directory — so there's
  * nothing else to resolve from flags. An explicit `name` is validated and
  * refused on collision; an omitted one is auto-derived and uniquified. Create
  * and fork differ only in what they resolve up front (nothing vs. the source
  * flow), the default filename, and which [[AuthorAction]] method launches the
  * flow.
  */
private[cli] object AuthorCli:

  def create(
      name: Option[String],
      goal: String,
      global: Flag,
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
      workDir = workDir,
      resolveSource = Right(()),
      defaultFileName = _ => FlowAuthoring.suggestFilenameForGoal(goal),
      launch = (_, params, ui, terminal) =>
        AuthorAction.create(goal, params, ui, terminal)
    )

  def fork(
      source: String,
      name: Option[String],
      changes: String,
      global: Flag,
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
      workDir = workDir,
      resolveSource =
        FlowResolution.resolve(source, workDir).left.map(actionFailure),
      defaultFileName = src => FlowAuthoring.forkFilenameDefault(src.name),
      launch = (src, params, ui, terminal) =>
        AuthorAction.fork(src, changes, params, ui, terminal)
    )

  /** The pipeline `create` and `fork` share (ADR 0021 §9). `resolveSource`
    * yields the fork's source flow (or `()` for create) before the tier flag is
    * resolved; `defaultFileName` derives the auto filename from it lazily, only
    * when no `name` was given; `launch` hands the prepared target to the
    * matching authoring action.
    */
  private def runAuthor[S](
      command: String,
      tty: Boolean,
      blankArg: String,
      blankValue: String,
      name: Option[String],
      global: Flag,
      workDir: os.Path,
      resolveSource: => Either[CliFailure, S],
      defaultFileName: S => String,
      launch: (S, AuthorParams, ShellUi, Terminal) => LaunchResult
  ): Int =
    val globalFlows = GlobalSettings.defaultFlows
    complete:
      for
        _ <- requireTty(command, tty).left.map(usageFailure)
        _ <- requireNonBlank(blankArg, blankValue).left.map(usageFailure)
        source <- resolveSource
        tier = if global.value then CreateTier.Global else CreateTier.Project
        target <- resolveTarget(
          tier,
          name,
          defaultFileName(source),
          workDir,
          globalFlows
        )
      yield launchAuthoring(target, AuthorParams(tier, target), source, launch)

  /** An explicit `name` goes through validation + collision refusal (the caller
    * chose it — silently renaming would be surprising); an omitted one is
    * auto-derived and uniquified via [[FlowAuthoring.prepareAutoTarget]], which
    * never fails.
    */
  private def resolveTarget(
      tier: CreateTier,
      name: Option[String],
      autoName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[CliFailure, CreateTarget] =
    name match
      case Some(explicit) =>
        for
          _ <- validateFileName(explicit).left.map(usageFailure)
          target <- safePrepareTarget(
            tier,
            explicit,
            workDir,
            globalFlows
          ).left.map(actionFailure)
        yield target
      case None =>
        Right(
          FlowAuthoring.prepareAutoTarget(tier, autoName, workDir, globalFlows)
        )

  private def launchAuthoring[S](
      target: CreateTarget,
      params: AuthorParams,
      source: S,
      launch: (S, AuthorParams, ShellUi, Terminal) => LaunchResult
  ): Int =
    ShellOutput.info(s"target flow: ${target.flowPath}")
    Cli.withTerminal: terminal =>
      val ui = ShellUi.make(terminal)
      Cli.exitCodeFor(launch(source, params, ui, terminal))

  /** Forwards to [[FlowAuthoring.validateFileName]], kept as a thin alias since
    * existing call sites/tests spell it `AuthorCli.*`.
    */
  private[cli] def validateFileName(fileName: String): Either[String, Unit] =
    FlowAuthoring.validateFileName(fileName)

  /** Forwards to [[FlowAuthoring.safePrepareTarget]] — see
    * [[validateFileName]]'s scaladoc.
    */
  private[cli] def safePrepareTarget(
      tier: CreateTier,
      fileName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, CreateTarget] =
    FlowAuthoring.safePrepareTarget(tier, fileName, workDir, globalFlows)
