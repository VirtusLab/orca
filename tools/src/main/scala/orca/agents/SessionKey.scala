package orca.agents

/** What identifies a durable session: the `name` it was minted under (its role,
  * e.g. `implementer`) and the stage that minted it. Two stages can therefore
  * never name the same session, and one stage cannot mint the same name twice.
  *
  * `stage` is the minting stage's hierarchical path id (`name#occurrence`
  * segments joined by `/`, ADR 0018 §2.1), or the empty string for a session
  * minted in the flow body outside any stage. Like every stage path id it is
  * opaque — compared for exact equality, never parsed.
  *
  * Minted by `agent.session(name, seed)`, persisted in the progress log as an
  * [[orca.progress.SessionRecord]], and carried onto
  * [[orca.events.OrcaEvent.SessionCommitted]] so the run manifest and the
  * shell's session picker can tell same-named sessions apart.
  */
case class SessionKey(name: String, stage: String):
  /** How the key reads in a run's own diagnostics: the name, and the stage that
    * owns it. Diagnostic, not a label — a stage path id carries `#0` occurrence
    * suffixes, which is why the shell's picker and `orca continue --list` show
    * the name and give the stage a column of its own instead of calling this.
    */
  def describe: String =
    if stage.isEmpty then s"'$name'" else s"'$name' in stage '$stage'"
