package orca.util

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** Opaque wrapper for "a JSON subtree, held in its serialized form". Used for
  * message fields whose shape varies too much to be worth modelling — tool
  * inputs, MCP tool arguments, structured-output payloads, MCP tool results —
  * and for the progress log's type-erased stage results, where embedding the
  * subtree verbatim (rather than as an escaped string) keeps the file readable
  * when debugging. The codec reads and writes the raw bytes straight through
  * without re-parsing.
  *
  * Public (not `private[orca]`) because it appears in the public progress-log
  * surface: [[orca.progress.StageEntry.resultJson]], reachable through the
  * `ProgressStore` a `flow(...)` caller may supply.
  *
  * `nullValue` is the literal four-character string `"null"` — i.e., a missing
  * field decodes indistinguishably from an actual JSON `null`. Fields that need
  * to detect absence must wrap `RawJson` in `Option`.
  */
opaque type RawJson = String

object RawJson:
  def apply(s: String): RawJson = s

  extension (r: RawJson) def value: String = r

  given JsonValueCodec[RawJson] with
    def decodeValue(in: JsonReader, default: RawJson): RawJson =
      // `readRawValAsBytes` returns the next JSON value verbatim, including
      // whitespace around nested structures; we re-interpret as UTF-8.
      new String(
        in.readRawValAsBytes(),
        java.nio.charset.StandardCharsets.UTF_8
      )

    def encodeValue(x: RawJson, out: JsonWriter): Unit =
      // Writing from the String directly avoids an intermediate
      // `getBytes` allocation — jsoniter's writer converts once.
      out.writeRawVal(x.value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    def nullValue: RawJson = "null"

  /** For `Schema.derived` on container types (the progress log's
    * [[orca.progress.StageEntry]]). Those containers are persistence DTOs, not
    * LLM-facing output types, so this schema is never rendered into an agent's
    * `--json-schema`; `string` is the closest honest primitive for "an opaque
    * serialized subtree".
    */
  given sttp.tapir.Schema[RawJson] = sttp.tapir.Schema.string
