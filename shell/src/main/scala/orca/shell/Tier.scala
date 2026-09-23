package orca.shell

/** Where the shell writes a flow or settings file: the project's committed
  * `.orca/`, or the user-global config home. The writable subset of
  * [[orca.discovery.Origin]], which also has the read-only built-in tier.
  */
private[shell] enum Tier:
  case Project, Global
