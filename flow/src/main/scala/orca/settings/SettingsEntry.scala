package orca.settings

/** One entry of a rendered settings file — what auto-discovery hands to
  * [[SettingsFile.render]] (ADR 0019).
  */
private[orca] enum SettingsEntry:
  /** `key = command`, with an optional comment carrying the discovery evidence,
    * rendered as `# ` line(s) directly above the command line.
    *
    * `command` is non-blank and does not start with `#` (even after render's
    * newline collapse): discovery's mechanical checks demote blank and
    * unresolvable commands to [[Demoted]] before render sees them, so render
    * does not re-validate. A violating command would render as a line the
    * parser drops (blank value) or rejects (`#`-leading `CommentedValue`).
    */
  case Command(key: String, command: String, comment: Option[String])

  /** Rendered commented-out, so the task stays unset and the line is invisible
    * to the parser:
    * {{{
    * # key =   (reason)
    * }}}
    */
  case Unset(key: String, reason: String)

  /** A discovered command that failed a mechanical check (ADR 0019), rendered
    * commented-out with the failure reason — invisible to the parser like any
    * `#` line, but the command stays visible for the user to fix and
    * un-comment:
    * {{{
    * # key = command   (reason)
    * }}}
    */
  case Demoted(key: String, command: String, reason: String)
