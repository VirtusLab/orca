package orca.shell.cli

import mainargs.Flag
import org.jline.terminal.Terminal
import orca.shell.{ShellEnv, Tier}
import orca.shell.actions.{AuthorAction, FlowResolution}
import orca.shell.create.{FlowAuthoring, FlowDestination}
import orca.shell.run.{FlowLauncher, LaunchResult}
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
      goal: String,
      name: Option[String],
      global: Flag,
      tty: Boolean
  )(using ShellEnv): Int =
    runAuthor(
      command = "create",
      tty = tty,
      blankArg = "goal",
      blankValue = goal,
      name = name,
      global = global,
      resolveSource = Right(()),
      defaultFileName = _ => FlowAuthoring.suggestFilenameForGoal(goal),
      launch = (_, destination, ui, terminal) =>
        AuthorAction.create(
          goal,
          destination,
          ui,
          terminal,
          FlowLauncher.runAnnounced
        )
    )

  def fork(
      source: String,
      changes: String,
      name: Option[String],
      global: Flag,
      tty: Boolean
  )(using ShellEnv): Int =
    runAuthor(
      command = "fork",
      tty = tty,
      blankArg = "changes",
      blankValue = changes,
      name = name,
      global = global,
      resolveSource = FlowResolution.resolve(source).left.map(actionFailure),
      defaultFileName = src =>
        FlowAuthoring
          .suggestFilenameForFork(src.name, src.description, changes),
      launch = (src, destination, ui, terminal) =>
        AuthorAction.fork(
          src,
          changes,
          destination,
          ui,
          terminal,
          FlowLauncher.runAnnounced
        )
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
      resolveSource: => Either[CliFailure, S],
      defaultFileName: S => String,
      launch: (S, FlowDestination, ShellUi, Terminal) => LaunchResult
  )(using ShellEnv): Int =
    complete:
      for
        _ <- requireTty(command, tty).left.map(usageFailure)
        _ <- requireNonBlank(blankArg, blankValue).left.map(usageFailure)
        source <- resolveSource
        tier = if global.value then Tier.Global else Tier.Project
        target <- resolveTarget(tier, name, defaultFileName(source))
      yield launchAuthoring(target, source, launch)

  /** An explicit `name` goes through validation + collision refusal (the caller
    * chose it — silently renaming would be surprising); an omitted one is
    * auto-derived and uniquified via [[FlowAuthoring.prepareAutoTarget]], which
    * never fails. `autoName` is by-name so an explicit `name` never triggers
    * the (possibly agent-backed, multi-second) suggestion call at all — not
    * just discards its result. `private[cli]` so a test can verify that by-name
    * laziness without going through [[runAuthor]]'s tty gate and real terminal.
    */
  private[cli] def resolveTarget(
      tier: Tier,
      name: Option[String],
      autoName: => String
  )(using ShellEnv): Either[CliFailure, FlowDestination] =
    name match
      case Some(explicit) =>
        for
          _ <- FlowAuthoring.validateFileName(explicit).left.map(usageFailure)
          target <- FlowAuthoring
            .safePrepareTarget(tier, explicit)
            .left
            .map(actionFailure)
        yield target
      case None =>
        ShellOutput.info("picking a filename…")
        Right(FlowAuthoring.prepareAutoTarget(tier, autoName))

  private def launchAuthoring[S](
      target: FlowDestination,
      source: S,
      launch: (S, FlowDestination, ShellUi, Terminal) => LaunchResult
  ): Int =
    ShellOutput.info(s"target flow: ${target.flowPath}")
    Cli.withTerminal: terminal =>
      val ui = ShellUi.make(terminal)
      Cli.exitCodeFor(launch(source, target, ui, terminal))
