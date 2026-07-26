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
    * as the same LIVE `key = off` line as [[Unset]] — the rejected command and
    * failure reason are folded into the informative comment above, so a
    * reviewer sees what was tried and can fix it by hand:
    * {{{
    * # command: reason
    * key = off
    * }}}
    */
  case Demoted(key: String, command: String, reason: String)
