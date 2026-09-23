package orca.shell.cli

import orca.shell.ShellEnv
import orca.shell.actions.FlowResolution

/** `orca list`'s behavior (ADR 0021 §10/§5): discover flows across the three
  * tiers and render them as a table or JSON ([[Tables]]).
  */
private[cli] object ListCli:

  private[cli] def runList(json: Boolean)(using ShellEnv): Int =
    FlowResolution.list match
      case Left(message) =>
        Cli.diagnostic(message)
        ExitCodes.ActionFailed
      case Right(flows) =>
        Tables.printFlows(flows, json)
        ExitCodes.Ok
