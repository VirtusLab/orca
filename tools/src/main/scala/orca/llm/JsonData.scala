package orca.llm

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
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

given schemaFromJsonData[A](using jd: JsonData[A]): Schema[A] = jd.schema

given codecFromJsonData[A](using jd: JsonData[A]): ConfiguredJsonValueCodec[A] =
  jd.codec
