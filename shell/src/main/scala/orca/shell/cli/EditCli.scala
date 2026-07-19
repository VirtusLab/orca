package orca.shell.cli

import orca.settings.GlobalSettings
import orca.shell.actions.{EditAction, FlowResolution}
import orca.shell.create.CreateTier
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}

import Cli.{actionFailure, complete, requireTty, usageFailure, withTerminal}

/** `orca edit`'s behavior (ADR 0021 §10/§6): tty-gate, resolve the flow, then
  * edit it in place — or, for a built-in, copy it into the `--to` tier and edit
  * the copy.
  */
private[cli] object EditCli:

  def run(
      flowRef: String,
      to: Option[String],
      tty: Boolean,
      workDir: os.Path
  ): Int =
    complete:
      for
        _ <- requireTty("edit", tty).left.map(usageFailure)
        flow <- FlowResolution.resolve(flowRef, workDir).left.map(actionFailure)
        exit <- editResolved(flow, to, workDir)
      yield exit

  private def editResolved(
      flow: DiscoveredFlow,
      to: Option[String],
      workDir: os.Path
  ): Either[CliFailure, Int] =
    if flow.origin != FlowOrigin.BuiltIn then
      if to.isDefined then
        Left(usageFailure("--to only applies when customizing a built-in flow"))
      // propagates the editor child's raw exit code — same
      // wraps-a-subprocess convention as run/continue.
      else Right(withTerminal(EditAction.editInPlace(_, flow.path)))
    else
      to match
        case None =>
          Left(
            usageFailure(
              "'" + flow.name + "' is built-in — pass --to project|global to customize it"
            )
          )
        case Some(raw) =>
          for
            tier <- parseCustomizeTier(raw).left.map(usageFailure)
            exit <- withTerminal(
              EditAction.customizeThenEdit(
                _,
                flow,
                tier,
                workDir,
                GlobalSettings.defaultFlows
              )
            ).left.map(actionFailure)
          yield exit

  private[cli] def parseCustomizeTier(
      raw: String
  ): Either[String, CreateTier] =
    raw match
      case "project" => Right(CreateTier.Project)
      case "global"  => Right(CreateTier.Global)
      case other => Left(s"--to must be 'project' or 'global', got '$other'")
