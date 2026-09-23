package orca.gitref

import orca.agents.JsonData

/** A git commit hash that has passed the shape check, so what carries it can't
  * be confused with an arbitrary string.
  *
  * [[CommitHash.from]] is the only way in. The JSON codec decodes through it
  * too, so a persisted document holding something else fails to parse rather
  * than reaching git.
  */
opaque type CommitHash = String

object CommitHash:
  /** The hash `s` names, or `None` when it can't be one: hex only (an edited
    * header must not reach `git` as an option or a path), and at least an
    * abbreviation git resolves unambiguously — a one- or two-character hex
    * value matches too many objects to be a diff base.
    */
  def from(s: String): Option[CommitHash] =
    Option.when(s.length >= MinAbbrevLength && s.matches("[0-9a-fA-F]+"))(s)

  /** git's own floor for an abbreviated hash (`core.abbrev` never goes below
    * this), so anything shorter is not a hash a caller could have meant.
    */
  private val MinAbbrevLength: Int = 4

  given JsonData[CommitHash] =
    JsonData.fromString(
      s => from(s).toRight(s"not a commit hash: $s"),
      identity
    )

  extension (h: CommitHash)
    def value: String = h

    /** The hash abbreviated for display (at most 12 hex chars). */
    def short: String = h.take(12)
