package orca.shell.create

import orca.shell.{ShellEnv, Tier}

/** Where an authored flow is written (ADR 0021 §9). A Project flow is committed
  * into `repo` once authored; a Global one has no repo.
  */
private[shell] enum FlowDestination:
  case Project(flowPath: os.Path, repo: os.Path)
  case Global(flowPath: os.Path)

  def flowPath: os.Path

private[shell] object FlowDestination:
  /** `flowPath` in `tier`; a Project flow's repo is the shell's `workDir`. */
  def of(tier: Tier, flowPath: os.Path)(using env: ShellEnv): FlowDestination =
    tier match
      case Tier.Project => Project(flowPath, env.workDir)
      case Tier.Global  => Global(flowPath)
