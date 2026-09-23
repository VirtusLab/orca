package orca.shell

import orca.OrcaDir
import ox.tap

/** Where the shell writes a flow or settings file: the project's committed
  * `.orca/`, or the user-global config home. The writable subset of
  * [[orca.discovery.Origin]], which also has the read-only built-in tier.
  */
private[shell] enum Tier:
  case Project, Global

private[shell] object Tier:
  extension (tier: Tier)
    /** The tier's flows directory, created if absent: `.orca/flows/` under
      * `workDir`, or the global `flows/`.
      */
    def ensureFlowsDir(using env: ShellEnv): os.Path =
      tier match
        case Tier.Project => OrcaDir.ensureFlows(env.workDir)
        case Tier.Global  => env.configHome.flows.tap(os.makeDir.all(_))

    /** The tier's settings file: `.orca/settings.properties` under `workDir`,
      * or the global `settings.properties`.
      */
    def settingsPath(using env: ShellEnv): os.Path =
      tier match
        case Tier.Project => OrcaDir.settingsPath(env.workDir)
        case Tier.Global  => env.configHome.settings
