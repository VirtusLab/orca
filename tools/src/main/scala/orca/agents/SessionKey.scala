package orca.agents

/** The pair that identifies a durable session: the `name` it was minted under
  * (its role, e.g. `implementer`) and the `detail` telling it apart from the
  * other sessions sharing that name — typically the task it serves. Free text,
  * compared by exact string equality, and never used as a filename or a wire
  * token, so it needs no escaping. Empty for a session that is the only one
  * under its name.
  *
  * Minted by `agent.session(name, detail, seed)`, persisted in the progress log
  * as an [[orca.progress.SessionRecord]], and carried onto
  * [[orca.events.OrcaEvent.SessionCommitted]] so the run manifest and the
  * shell's session picker can tell same-named sessions apart.
  */
case class SessionKey(name: String, detail: String):
  /** How the key reads in user-facing text: the name alone for a session with
    * no detail, `name (detail)` otherwise. The single home of that rule — every
    * surface that shows a session to a person calls this.
    */
  def label: String = if detail.isEmpty then name else s"$name ($detail)"
