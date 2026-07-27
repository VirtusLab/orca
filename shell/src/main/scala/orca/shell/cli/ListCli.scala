package orca.shell.cli

import orca.shell.actions.FlowResolution

/** `orca list`'s behavior (ADR 0021 §10/§5): discover flows across the three
  * tiers and render them as a table or JSON ([[Tables]]).
  */
private[cli] object ListCli:

  /** `list`'s full behavior over an explicit `workDir` (test seam). */
  private[cli] def runList(workDir: os.Path, json: Boolean): Int =
    FlowResolution.list(workDir) match
      case Left(message) =>
        Cli.diagnostic(message)
        ExitCodes.ActionFailed
      case Right(flows) =>
        Tables.printFlows(flows, json)
        ExitCodes.Ok
