package orca.review

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import orca.OrcaFlowException
import sttp.tapir.{Schema, Validator}

/** Returned in the `Left` of [[Confidence.apply]], and thrown at the wire
  * boundary when a reviewer reports a number outside `[0, 1]`.
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
  def apply(value: Double): Either[InvalidConfidence, Confidence] =
    if value >= 0.0 && value <= 1.0 then Right(value)
    else Left(new InvalidConfidence(value))

  extension (c: Confidence)
    def value: Double = c

    /** Does `c` clear `bar`? Inclusive: a finding exactly on the bar passes. */
    def clears(bar: Confidence): Boolean = c >= bar

  given JsonValueCodec[Confidence] with
    def decodeValue(in: JsonReader, default: Confidence): Confidence =
      val raw = in.readDouble()
      Confidence(raw) match
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
