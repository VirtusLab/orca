package orca.util

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** Opaque wrapper for a JSON subtree held in its serialized form. Used for
  * message fields whose shape varies too much to model (tool inputs, MCP
  * arguments and results, structured-output payloads) and for the progress
  * log's type-erased stage results, where embedding the subtree verbatim keeps
  * the file readable. The codec reads and writes raw bytes without re-parsing.
  *
  * Public because it appears in the progress-log surface
  * ([[orca.progress.StageEntry.resultJson]]).
  *
  * `nullValue` is the string `"null"`, so a missing field decodes
  * indistinguishably from a JSON `null`; fields that must detect absence wrap
  * `RawJson` in `Option`.
  */
opaque type RawJson = String

object RawJson:
  def apply(s: String): RawJson = s

  extension (r: RawJson) def value: String = r

  given JsonValueCodec[RawJson] with
    def decodeValue(in: JsonReader, default: RawJson): RawJson =
      // The next JSON value verbatim, re-interpreted as UTF-8.
      new String(
        in.readRawValAsBytes(),
        java.nio.charset.StandardCharsets.UTF_8
      )

    def encodeValue(x: RawJson, out: JsonWriter): Unit =
      out.writeRawVal(x.value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    def nullValue: RawJson = "null"

  /** For `Schema.derived` on container types (e.g.
    * [[orca.progress.StageEntry]]). These are persistence DTOs, never rendered
    * into an agent's `--json-schema`; `string` is the closest primitive for an
    * opaque serialized subtree.
    */
  given sttp.tapir.Schema[RawJson] = sttp.tapir.Schema.string
