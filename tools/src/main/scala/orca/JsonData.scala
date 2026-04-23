package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

import scala.deriving.Mirror

/** Bundles a tapir `Schema` and a jsoniter-scala `ConfiguredJsonValueCodec` for
  * a type. Flow scripts replace `derives Schema, ConfiguredJsonValueCodec` with
  * `derives JsonData` on case classes that travel in and out of LLM calls as
  * structured JSON.
  *
  * Package-level forwarder givens (below) expose `Schema[A]` and
  * `ConfiguredJsonValueCodec[A]` whenever a `JsonData[A]` is in scope, so every
  * API that still bounds on those underlying typeclasses continues to resolve
  * them without an explicit import.
  */
trait JsonData[A]:
  def schema: Schema[A]
  def codec: ConfiguredJsonValueCodec[A]

object JsonData:

  def apply[A](
      schemaInstance: Schema[A],
      codecInstance: ConfiguredJsonValueCodec[A]
  ): JsonData[A] =
    new JsonData[A]:
      val schema: Schema[A] = schemaInstance
      val codec: ConfiguredJsonValueCodec[A] = codecInstance

  inline def derived[A](using Mirror.Of[A]): JsonData[A] =
    apply(Schema.derived[A], ConfiguredJsonValueCodec.derived[A])

// Top-level so they're always in implicit scope under `package orca`; a file
// importing `orca.*` — or a file that lives in package `orca` itself — sees
// them without needing to import `JsonData.given` explicitly.

given schemaFromJsonData[A](using jd: JsonData[A]): Schema[A] = jd.schema

given codecFromJsonData[A](using jd: JsonData[A]): ConfiguredJsonValueCodec[A] =
  jd.codec
