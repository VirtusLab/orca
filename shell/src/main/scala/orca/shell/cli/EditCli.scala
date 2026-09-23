package orca.shell.cli

import orca.shell.{ShellEnv, Tier}
import orca.shell.actions.{EditAction, FlowResolution}
import orca.discovery.Origin
import orca.shell.flows.DiscoveredFlow

import Cli.{actionFailure, complete, requireTty, usageFailure, withTerminal}

/** `orca edit`'s behavior (ADR 0021 §10/§6): tty-gate, resolve the flow, then
  * edit it in place — or, for a built-in, copy it into the `--to` tier and edit
  * the copy.
  */
private[cli] object EditCli:

  def run(flowRef: String, to: Option[Tier], tty: Boolean)(using
      ShellEnv
  ): Int =
    complete:
      for
        _ <- requireTty("edit", tty).left.map(usageFailure)
        flow <- FlowResolution.resolve(flowRef).left.map(actionFailure)
        exit <- editResolved(flow, to)
      yield exit

  private def editResolved(flow: DiscoveredFlow, to: Option[Tier])(using
      ShellEnv
  ): Either[CliFailure, Int] =
    if flow.origin != Origin.BuiltIn then
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
        case Some(tier) =>
          withTerminal(EditAction.customizeThenEdit(_, flow, tier)).left
            .map(actionFailure)
