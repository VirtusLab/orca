package orca.agents

/** A human-readable summary for a domain value. The library calls
  * `message(parsed)` after `AgentCall.resultAs[O]` succeeds and emits a `Step`
  * event with the result; `None` means "nothing to say" and is dropped
  * silently. Provide a specific given in the type's companion to opt into a
  * friendlier rendering.
  */
trait Announce[O]:
  def message(value: O): Option[String]

object Announce:

  /** Catch-all no-op so `Announce[O]` is always resolvable. Specific givens
    * (e.g. `given Announce[Plan]`) win via Scala 3's specificity rules.
    */
  given default[O]: Announce[O] = _ => None

  /** Construct from a function returning the message text. Empty strings are
    * normalised to `None` so call sites can write `Announce.from(x => if cond
    * then "" else "…")` without the empty-branch landing on screen as a blank
    * line.
    */
  def from[O](f: O => String): Announce[O] = (value: O) =>
    f(value) match
      case ""  => None
      case msg => Some(msg)

  /** Construct from a function returning `Option[String]` directly, for cases
    * where the empty/non-empty distinction is more naturally expressed at the
    * source.
    */
  def fromOption[O](f: O => Option[String]): Announce[O] =
    (value: O) => f(value)
