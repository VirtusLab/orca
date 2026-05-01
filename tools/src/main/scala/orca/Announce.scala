package orca

/** A human-readable summary for a domain value. The library calls
  * `message(parsed)` after `LlmCall.resultAs[O]` succeeds and emits a
  * `Step` event with the result; an empty string means "nothing to
  * say" and is dropped silently. Provide a specific given in the
  * type's companion to opt into a friendlier rendering.
  */
trait Announce[O]:
  def message(value: O): String

object Announce:

  // TODO: instead of using a magical value, use a structured output, if the "" is used by the callers. Maybe simply option?
  /** Catch-all no-op so `Announce[O]` is always resolvable. Specific
    * givens (e.g. `given Announce[Plan]`) win via Scala 3's
    * specificity rules.
    */
  given default[O]: Announce[O] = _ => ""

  /** Construct from a function: `given Announce[Foo] =
    * Announce.from(_.summary)`.
    */
  def from[O](f: O => String): Announce[O] = (value: O) => f(value)
