package orca.agents

/** A human-readable summary for a domain value. The library calls
  * `message(parsed)` after `AgentCall.resultAs[O]` succeeds and surfaces the
  * result as an `OrcaEvent.StructuredResult`. Provide a specific given in the
  * type's companion to opt into a friendlier rendering.
  *
  * From a specific given, `Some(text)` is the result summary and `None` means
  * "deliberately say nothing" (for results the call site already narrates, e.g.
  * the review loop's per-reviewer lines). A type with NO specific given
  * resolves to the catch-all [[Announce.default]], which renderers fall back to
  * showing the raw payload for — so an unannounced result never disappears
  * silently (ADR 0008; ADR 0009).
  */
trait Announce[O]:
  def message(value: O): Option[String]

object Announce:

  /** The catch-all's class — named (rather than a lambda) so
    * `DefaultAgentCall.emitStructuredResult` can distinguish "no specific
    * instance exists" (fall back to raw payload) from a specific instance
    * returning `None` (deliberate silence). Different display contracts.
    */
  final private[agents] class NoSpecific[O] extends Announce[O]:
    def message(value: O): Option[String] = None

  /** Catch-all no-op so `Announce[O]` is always resolvable. Specific givens
    * (e.g. `given Announce[Plan]`) take precedence.
    */
  given default[O]: Announce[O] = NoSpecific[O]()

  /** Construct from a function returning the message text. Empty strings are
    * normalised to `None` so call sites can write `Announce.from(x => if cond
    * then "" else "…")` without the empty-branch landing on screen as a blank
    * line.
    */
  def from[O](f: O => String): Announce[O] = (value: O) =>
    f(value) match
      case ""  => None
      case msg => Some(msg)

  /** Construct from a function returning `Option[String]` directly. */
  def fromOption[O](f: O => Option[String]): Announce[O] =
    (value: O) => f(value)
