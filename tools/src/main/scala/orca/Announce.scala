package orca

/** A human-readable summary for a domain value, surfaced by the library's
  * structured-call surface (`LlmCall.resultAs[O]`) after parsing.
  *
  * The renderer no longer second-guesses the agent's output — every delta the
  * agent emits lands in the event log verbatim, JSON or not. When a parsed `O`
  * *also* has an `Announce[O]` instance with a non-empty message, the library
  * emits a `Step` event with that message after the agent's turn ends, giving
  * the human a friendly read of the same value.
  *
  * The default given (below) returns an empty string, which the library treats
  * as "no announcement to make" — so adding the `Announce` context bound to
  * `LlmCall.resultAs` doesn't break any caller. Provide a specific given
  * (typically in the case class's companion) when a friendlier read is worth
  * showing.
  */
trait Announce[O]:
  def message(value: O): String

object Announce:

  /** Catch-all no-op so every type works — `Announce[O]` is always resolvable.
    * Specific givens (e.g. `given Announce[Plan]`) win via Scala 3's
    * specificity rules.
    */
  given default[O]: Announce[O] = _ => ""

  /** Construct from a function. Convenient when the domain type's companion
    * wants `given Announce[Foo] = Announce.from(_.summary)` without spelling
    * out the trait body.
    */
  def from[O](f: O => String): Announce[O] = (value: O) => f(value)

  // The manual-trigger extension `value.announce` lives in
  // `flow/src/main/scala/orca/announceExtension.scala` because it
  // depends on `FlowContext`, which is part of the flow module.
  // Here we keep just the typeclass + default + helper.
