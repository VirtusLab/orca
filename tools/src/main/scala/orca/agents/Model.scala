package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import sttp.tapir.Schema

/** The concrete model id a backend served (or will serve) a call with —
  * `claude-haiku-4-5`, `gpt-5-mini`, … Opaque over `String` so it can't mix
  * with other free-floating strings; the bare string is recovered via `.name`.
  * Whatever the underlying CLI reports or accepts on `--model`; orca doesn't
  * normalise or validate it.
  */
opaque type Model = String

object Model:
  def apply(s: String): Model = s
  extension (m: Model) def name: String = m

  given JsonValueCodec[Model] with
    def decodeValue(in: JsonReader, default: Model): Model =
      in.readString(default)
    def encodeValue(value: Model, out: JsonWriter): Unit = out.writeVal(value)
    // Model ids travel as `Option[String]` at the wire layer and wrap to Model
    // at the seam, so a bare null reaching this codec is a protocol mistake to
    // surface rather than smuggle past the opaque-type boundary.
    def nullValue: Model = throw new IllegalStateException(
      "Model cannot decode JSON null; wrap in Option at the wire layer"
    )

  given Schema[Model] = Schema.string[Model]
