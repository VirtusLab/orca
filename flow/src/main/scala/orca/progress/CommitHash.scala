package orca.progress

/** A git commit hash that has passed the shape check, so what carries it can't
  * be confused with an arbitrary string from the progress header.
  *
  * Like [[FeatureBranch]], the type is the guarantee: [[CommitHash.from]] is
  * the only way in, and [[value]] is unwrapped at the `GitTool` call site. The
  * header field itself stays a raw `Option[String]` — it is untrusted wire data
  * that must keep decoding leniently.
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

  extension (h: CommitHash)
    /** Unwrap for the git layer — call at the `GitTool` call site, not earlier.
      */
    def value: String = h
