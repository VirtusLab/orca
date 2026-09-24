package orca.backend

/** Tracks the tool-call ids of `ask_user` invocations whose wire echo must be
  * dropped from the turn's event stream.
  *
  * A bridged `ask_user` question is already surfaced as a
  * [[TurnEvent.UserQuestion]], so re-emitting the agent's tool-call block and
  * paired tool-result would render the exchange twice. Each decoder suppresses
  * the tool-call and drops its matching result.
  *
  * Only this id bookkeeping is shared; the matcher for "is this an `ask_user`
  * call" stays per call site, because backends name the tool differently
  * (claude `mcp__<server>__<tool>`, codex a `(server, tool)` pair, gemini
  * `<server>__<tool>` or the bare slug — and gemini must NOT match a name
  * merely containing the slug).
  */
private[orca] final case class AskUserEchoes(ids: Set[String]):

  /** Remember `id` so the paired tool-result echo is dropped when it arrives.
    */
  def suppress(id: String): AskUserEchoes = AskUserEchoes(ids + id)

  /** The echoes left once `id`'s is dropped, or `None` when `id` was never
    * suppressed and its tool-result should surface. The echo arrives once.
    */
  def consume(id: String): Option[AskUserEchoes] =
    Option.when(ids.contains(id))(AskUserEchoes(ids - id))

private[orca] object AskUserEchoes:
  val empty: AskUserEchoes = AskUserEchoes(Set.empty)
