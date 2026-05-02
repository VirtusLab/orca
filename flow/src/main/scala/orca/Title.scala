package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import sttp.tapir.Schema

/** Short user-facing label for a `Plan.Task` or `ReviewIssue` — the one-line
  * tag rendered in the event log, shown in the `## Task: …` markdown header,
  * and used by the fixing agent to identify which issue it addressed. Opaque
  * over `String` so the compiler rejects accidental mixing with `description`,
  * `reason`, or raw user input.
  */
opaque type Title = String

object Title:
  def apply(s: String): Title = s
  extension (t: Title) def value: String = t

  // Title is `String` at runtime, so jsoniter's existing String codec semantics
  // apply directly — we just retype them.
  given JsonValueCodec[Title] with
    def decodeValue(in: JsonReader, default: Title): Title =
      in.readString(default)
    def encodeValue(value: Title, out: JsonWriter): Unit = out.writeVal(value)
    def nullValue: Title = null.asInstanceOf[Title]

  given Schema[Title] = Schema.string[Title]
