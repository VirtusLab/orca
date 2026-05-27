package orca.tools.codex.jsonl

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** Opaque wrapper for "a JSON subtree, held in its serialized form". Used for
  * message fields whose shape varies too much to worth modelling — MCP tool
  * arguments, MCP tool results. The codec reads and writes the raw bytes
  * straight through without re-parsing.
  *
  * `nullValue` is the literal four-character string `"null"` — i.e., a missing
  * field decodes indistinguishably from an actual JSON `null`. Fields that need
  * to detect absence must wrap `RawJson` in `Option`.
  */
private[codex] opaque type RawJson = String

private[codex] object RawJson:
  def apply(s: String): RawJson = s

  extension (r: RawJson) def value: String = r

  given JsonValueCodec[RawJson] with
    def decodeValue(in: JsonReader, default: RawJson): RawJson =
      new String(
        in.readRawValAsBytes(),
        java.nio.charset.StandardCharsets.UTF_8
      )

    def encodeValue(x: RawJson, out: JsonWriter): Unit =
      out.writeRawVal(x.value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    def nullValue: RawJson = "null"
