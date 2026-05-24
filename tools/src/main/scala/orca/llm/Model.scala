package orca.llm

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import sttp.tapir.Schema

/** The concrete model id a backend served (or will serve) a call with —
  * `claude-haiku-4-5`, `gpt-5-mini`, … Opaque over `String` so the compiler
  * rejects accidental mixing with `agent`, `sessionId`, raw prompts, or other
  * free-floating strings; the bare string is recovered via `.name`.
  *
  * The string is whatever the underlying CLI reports or accepts on the
  * `--model` flag; orca doesn't normalise or validate it.
  */
opaque type Model = String

object Model:
  def apply(s: String): Model = s
  extension (m: Model) def name: String = m

  // Model is `String` at runtime, so jsoniter's existing String codec
  // semantics apply directly — we just retype them.
  given JsonValueCodec[Model] with
    def decodeValue(in: JsonReader, default: Model): Model =
      in.readString(default)
    def encodeValue(value: Model, out: JsonWriter): Unit = out.writeVal(value)
    // Fail loudly on a raw JSON null — the convention is that model ids
    // travel as `Option[String]` at the wire layer and wrap to Model at
    // the seam, so a bare null reaching this codec is a protocol mistake
    // we'd rather surface than smuggle past the opaque-type boundary.
    def nullValue: Model = throw new IllegalStateException(
      "Model cannot decode JSON null; wrap in Option at the wire layer"
    )

  given Schema[Model] = Schema.string[Model]
