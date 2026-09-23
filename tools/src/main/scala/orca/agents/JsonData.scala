package orca.agents

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

import scala.compiletime.{constValueTuple, erasedValue, summonFrom}
import scala.deriving.Mirror

/** Bundles a tapir `Schema` and a jsoniter-scala `ConfiguredJsonValueCodec` for
  * a type. Flow scripts use `derives JsonData` on case classes that travel in
  * and out of LLM calls as structured JSON.
  *
  * Scripts must import via `import orca.{*, given}` — `derives JsonData` on a
  * case class with nested case-class fields needs the forwarder givens below in
  * scope.
  *
  * A parameterless enum travels as its case name, and its schema is a string
  * enum listing every case. An enum nested in a `JsonData` type needs its own
  * `derives JsonData` to travel that way. A sum type whose cases carry fields
  * gets a schema its codec does not match (the codec adds a `"type"` field), so
  * it can be a stage result but not a `resultAs` output.
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

  /** A type that travels as a JSON string: written with `render`, read back
    * through `parse`, so a persisted value `parse` refuses fails to decode.
    */
  def fromString[A](
      parse: String => Either[String, A],
      render: A => String
  ): JsonData[A] =
    apply(
      Schema.schemaForString.as[A],
      new ConfiguredJsonValueCodec[A]:
        def decodeValue(in: JsonReader, default: A): A =
          in.readString(null) match
            case null => in.decodeError("expected a string")
            case s    => parse(s).fold(in.decodeError, identity)
        def encodeValue(x: A, out: JsonWriter): Unit = out.writeVal(render(x))
        def nullValue: A = null.asInstanceOf[A]
    )

  inline def derived[A](using m: Mirror.Of[A]): JsonData[A] =
    inline m match
      case s: Mirror.SumOf[A] =>
        inline if allSingletons[s.MirroredElemTypes] then stringEnum[A](s)
        else general[A]
      case _ => general[A]

  private inline def general[A](using Mirror.Of[A]): JsonData[A] =
    apply(
      Schema.derived[A],
      ConfiguredJsonValueCodec.derived[A](using strictCodecConfig)
    )

  // jsoniter writes each case as its Mirror label, so the schema lists the
  // labels too rather than relying on `toString`.
  private inline def stringEnum[A](s: Mirror.SumOf[A]): JsonData[A] =
    val labels =
      constValueTuple[s.MirroredElemLabels].toList.map(_.toString).toVector
    apply(
      Schema.derivedEnumeration[A](encode = Some(a => labels(s.ordinal(a)))),
      ConfiguredJsonValueCodec.derived[A](using
        strictCodecConfig.withDiscriminatorFieldName(None)
      )
    )

  private inline def allSingletons[T <: Tuple]: Boolean =
    inline erasedValue[T] match
      case _: EmptyTuple => true
      case _: (h *: t) =>
        summonFrom:
          case _: ValueOf[`h`] => allSingletons[t]
          case _               => false

  /** Wraps a plain `JsonValueCodec` as a `ConfiguredJsonValueCodec` (a marker
    * interface adding no methods) by delegating all calls. Used by the
    * hand-written primitive/generic givens below.
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

  given stringJsonData: JsonData[String] =
    apply(Schema.schemaForString, wrap(JsonCodecMaker.make))
  given intJsonData: JsonData[Int] =
    apply(Schema.schemaForInt, wrap(JsonCodecMaker.make))
  given longJsonData: JsonData[Long] =
    apply(Schema.schemaForLong, wrap(JsonCodecMaker.make))
  given booleanJsonData: JsonData[Boolean] =
    apply(Schema.schemaForBoolean, wrap(JsonCodecMaker.make))
  given doubleJsonData: JsonData[Double] =
    apply(Schema.schemaForDouble, wrap(JsonCodecMaker.make))

  /** Unit serialises as `{}`, a valid round-trippable "no meaningful payload"
    * value. `JsonCodecMaker` doesn't support `Unit`, so the codec is hand-
    * written. Decode is strict: it requires exactly `{}`, rejecting any other
    * token (including `null`), so `Option[Unit]` round-trips —
    * `None`/`Some(())` map to `null`/`{}`.
    */
  given unitJsonData: JsonData[Unit] = apply(
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
    apply(Schema.schemaForOption(using jd.schema), wrap(JsonCodecMaker.make))

  given [A](using jd: JsonData[A]): JsonData[List[A]] =
    given JsonValueCodec[A] = jd.codec
    // schemaForIterable returns Schema[Iterable[A]]; the cast to Schema[List[A]]
    // is safe because at runtime both are the same array schema with A elements.
    apply(
      Schema
        .schemaForIterable[A, List](using jd.schema)
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
