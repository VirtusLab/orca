package orca.review

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import orca.OrcaFlowException
import sttp.tapir.{Schema, Validator}

/** Thrown by [[Confidence.orThrow]] for a number outside `[0, 1]`. The decoder
  * reports the same range in a JSON decode error instead, so a reviewer's
  * out-of-range reply surfaces as malformed agent output rather than this.
  */
class InvalidConfidence(value: Double)
    extends OrcaFlowException(
      s"confidence must be a probability in [0, 1]; got $value"
    )

/** How sure a reviewer is that a [[ReviewIssue]] is real — a probability in
  * `[0, 1]`, which [[ConfidenceGate]] compares against a per-severity bar.
  *
  * Enforced at decode, so the range cannot be re-checked (or forgotten) per
  * call site. A percent-style `85` would otherwise clear every bar forever,
  * silently disabling the gate for that finding; rejecting it fails the turn
  * loudly instead, which is the outcome that gets the number restated.
  */
opaque type Confidence = Double

object Confidence:
  /** `value` as a [[Confidence]], throwing [[InvalidConfidence]] outside `[0,
    * 1]` — the constructor for a threshold written in source, where an
    * out-of-range number is a programming error, not agent output.
    *
    * It throws rather than returning an `Either` because an `Either` of an
    * opaque type is not usable outside this package: inference unifies the type
    * variable with the underlying `Double` and no call site type-checks.
    * [[decoded]] is the validating form, for the one caller that has to handle
    * a bad value rather than fail on it.
    */
  def orThrow(value: Double): Confidence =
    decoded(value) match
      case Right(c) => c
      case Left(e)  => throw e

  private[review] def decoded(
      value: Double
  ): Either[InvalidConfidence, Confidence] =
    if value >= 0.0 && value <= 1.0 then Right(value)
    else Left(new InvalidConfidence(value))

  extension (c: Confidence)
    /** The bare probability — for rendering it, which is the one thing
      * [[clears]] and the [[Ordering]] can't do. Comparisons go through those.
      */
    def value: Double = c

    /** Does `c` clear `bar`? Inclusive: a finding exactly on the bar passes. */
    def clears(bar: Confidence): Boolean = c >= bar

  /** Orders findings by how sure their reviewer is. In the companion, so
    * sorting a list of findings needs no import — and `.reverse` gives the
    * most-confident-first order a report usually wants.
    */
  given Ordering[Confidence] = Ordering.Double.TotalOrdering

  given JsonValueCodec[Confidence] with
    def decodeValue(in: JsonReader, default: Confidence): Confidence =
      decoded(in.readDouble()) match
        case Right(c) => c
        case Left(e)  => in.decodeError(e.getMessage)
    def encodeValue(value: Confidence, out: JsonWriter): Unit =
      out.writeVal(value)
    // Only ever handed to `decodeValue` as its `default`, which ignores it and
    // reads the wire value — never a decode result.
    def nullValue: Confidence = 0.0

  // The bounds are emitted as JSON Schema `minimum`/`maximum`, so the model
  // sees the range before it answers rather than only in a decode failure.
  given Schema[Confidence] = Schema.schemaForDouble
    .validate(Validator.min(0.0))
    .validate(Validator.max(1.0))
