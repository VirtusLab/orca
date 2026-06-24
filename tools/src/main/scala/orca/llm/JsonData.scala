package orca.llm

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}
import sttp.tapir.Schema

import scala.deriving.Mirror

/** Bundles a tapir `Schema` and a jsoniter-scala `ConfiguredJsonValueCodec` for
  * a type. Flow scripts use `derives JsonData` on case classes that travel in
  * and out of LLM calls as structured JSON.
  *
  * Scripts must import via `import orca.{*, given}` — Scala 3's plain wildcard
  * imports exclude givens, and `derives JsonData` on a case class with nested
  * case-class fields needs the forwarder givens below in scope.
  */
trait JsonData[A]:
  def schema: Schema[A]
  def codec: ConfiguredJsonValueCodec[A]

object JsonData:

  /** Stricter-than-default jsoniter config: missing `List` / `Set` / `Map`
    * fields fail to parse rather than defaulting to empty, so an agent reply
    * with the right overall shape but the wrong fields can't masquerade as a
    * "success with no content".
    */
  inline def strictCodecConfig: CodecMakerConfig =
    CodecMakerConfig
      .withRequireCollectionFields(true)
      .withTransientEmpty(false)

  def apply[A](
      schemaInstance: Schema[A],
      codecInstance: ConfiguredJsonValueCodec[A]
  ): JsonData[A] =
    new JsonData[A]:
      val schema: Schema[A] = schemaInstance
      val codec: ConfiguredJsonValueCodec[A] = codecInstance

  inline def derived[A](using Mirror.Of[A]): JsonData[A] =
    apply(
      Schema.derived[A],
      ConfiguredJsonValueCodec.derived[A](using strictCodecConfig)
    )

  /** Wraps a plain `JsonValueCodec` as a `ConfiguredJsonValueCodec`.
    * `ConfiguredJsonValueCodec` is a marker interface that extends
    * `JsonValueCodec` without adding methods, so we just delegate all calls.
    * Used by the hand-written primitive/generic givens below.
    */
  private def wrap[A](c: JsonValueCodec[A]): ConfiguredJsonValueCodec[A] =
    new ConfiguredJsonValueCodec[A]:
      def decodeValue(in: JsonReader, default: A): A =
        c.decodeValue(in, default)
      def encodeValue(x: A, out: JsonWriter): Unit = c.encodeValue(x, out)
      def nullValue: A = c.nullValue

  // ── Primitive givens ───────────────────────────────────────────────────────
  // Use the tapir Schema companion methods directly (not `summon`) to avoid
  // triggering the package-level `schemaFromJsonData` given, which would
  // reference the very instance being initialised (causing an infinite loop).

  given JsonData[String] =
    apply(Schema.schemaForString, wrap(JsonCodecMaker.make))
  given JsonData[Int] = apply(Schema.schemaForInt, wrap(JsonCodecMaker.make))
  given JsonData[Long] = apply(Schema.schemaForLong, wrap(JsonCodecMaker.make))
  given JsonData[Boolean] =
    apply(Schema.schemaForBoolean, wrap(JsonCodecMaker.make))
  given JsonData[Double] =
    apply(Schema.schemaForDouble, wrap(JsonCodecMaker.make))

  /** Unit serialises as `{}` (an empty JSON object) — a valid, round-trippable
    * JSON value that conveys "no meaningful payload". `JsonCodecMaker` does not
    * support `Unit`, so we write the codec by hand.
    *
    * Decode is strict: it requires exactly the empty object `{}` the encoder
    * produces, rejecting any other token (including `null`). So `Option[Unit]`
    * round-trips correctly — `None`/`Some(())` map to `null`/`{}`.
    */
  given JsonData[Unit] = apply(
    Schema.schemaForUnit,
    new ConfiguredJsonValueCodec[Unit]:
      def decodeValue(in: JsonReader, default: Unit): Unit =
        if in.isNextToken('{') then
          if !in.isNextToken('}') then in.objectEndOrCommaError()
        else in.decodeError("expected '{'")
      def encodeValue(x: Unit, out: JsonWriter): Unit =
        out.writeObjectStart()
        out.writeObjectEnd()
      def nullValue: Unit = ()
  )

  // ── Generic givens ─────────────────────────────────────────────────────────

  given [A](using jd: JsonData[A]): JsonData[Option[A]] =
    given JsonValueCodec[A] = jd.codec
    apply(Schema.schemaForOption(jd.schema), wrap(JsonCodecMaker.make))

  given [A](using jd: JsonData[A]): JsonData[List[A]] =
    given JsonValueCodec[A] = jd.codec
    // schemaForIterable returns Schema[Iterable[A]]; the cast to Schema[List[A]]
    // is safe because at runtime both are the same array schema with A elements.
    apply(
      Schema
        .schemaForIterable[A, List](jd.schema)
        .asInstanceOf[Schema[List[A]]],
      wrap(JsonCodecMaker.make)
    )

  given [A, B](using jdA: JsonData[A], jdB: JsonData[B]): JsonData[(A, B)] =
    given JsonValueCodec[A] = jdA.codec
    given JsonValueCodec[B] = jdB.codec
    apply(Schema.derived[(A, B)], wrap(JsonCodecMaker.make))

  given [A, B, C](using
      jdA: JsonData[A],
      jdB: JsonData[B],
      jdC: JsonData[C]
  ): JsonData[(A, B, C)] =
    given JsonValueCodec[A] = jdA.codec
    given JsonValueCodec[B] = jdB.codec
    given JsonValueCodec[C] = jdC.codec
    apply(Schema.derived[(A, B, C)], wrap(JsonCodecMaker.make))

given schemaFromJsonData[A](using jd: JsonData[A]): Schema[A] = jd.schema

given codecFromJsonData[A](using jd: JsonData[A]): ConfiguredJsonValueCodec[A] =
  jd.codec
