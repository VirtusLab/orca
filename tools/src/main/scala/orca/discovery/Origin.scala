package orca.discovery

/** Which of the three tiers a discovered file came from. Precedence is project
  * > global > built-in, and flows (ADR 0021 §5) and reviewers (ADR 0023) read
  * the same way because they share this vocabulary rather than mirroring it.
  */
private[orca] enum Origin:
  case Project, Global, BuiltIn

  /** The tier's user-facing label, as shown in listing rows and shadow
    * annotations.
    */
  def label: String = this match
    case Origin.Project => "project"
    case Origin.Global  => "global"
    case Origin.BuiltIn => "built-in"
