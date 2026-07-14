package orca.settings

/** One entry of a rendered settings file — what auto-discovery hands to
  * [[SettingsFile.render]] (ADR 0019).
  */
private[orca] enum SettingsEntry:
  /** `key = command`, with an optional comment carrying the discovery evidence,
    * rendered as `# ` line(s) directly above the command line.
    */
  case Command(key: String, command: String, comment: Option[String])

  /** Rendered commented-out, so the task stays unset and the line is invisible
    * to the parser:
    * {{{
    * # key =   (reason)
    * }}}
    */
  case Unset(key: String, reason: String)
