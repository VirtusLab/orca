package orca.settings

/** One entry of a rendered settings file — what auto-discovery hands to
  * [[SettingsFile.render]] (ADR 0019).
  */
private[orca] enum SettingsEntry:
  /** `key = command`, with an optional comment carrying the discovery evidence,
    * rendered as `# ` line(s) directly above the command line.
    *
    * Invariant: `command` is non-blank and does not start with `#` (even after
    * render's newline collapse) — discovery demotes blank and unresolvable
    * commands to [[Demoted]] before render sees them, so render does not
    * re-validate.
    */
  case Command(key: String, command: String, comment: Option[String])

  /** Rendered as a LIVE `key = off` line — the task stays disabled, but the
    * assignment still counts as "configured" so discovery doesn't re-run over
    * the same absence next time. `reason` is purely informative, one `#` line
    * above:
    * {{{
    * # reason
    * key = off
    * }}}
    */
  case Unset(key: String, reason: String)

  /** A discovered command that failed a mechanical check (ADR 0019), rendered
    * as a comment only, so a reviewer sees what was tried and why it was
    * skipped:
    * {{{
    * # skipped: key = command (reason)
    * }}}
    */
  case Demoted(key: String, command: String, reason: String)

  /** A bare LIVE `key = off` line, for a key whose every discovered command was
    * [[Demoted]].
    */
  case Off(key: String)
