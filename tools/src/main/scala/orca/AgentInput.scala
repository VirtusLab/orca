package orca

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Typeclass that serializes an arbitrary value into the string that gets
  * embedded in the prompt sent to the LLM. Every method that accepts an
  * `input: I` (e.g. `LlmCall.autonomous`, `startSession`, `interactive`)
  * requires an `AgentInput[I]` so callers don't have to pre-stringify their
  * arguments.
  *
  * Two givens ship out of the box: `String` passes through verbatim, and
  * any type with a `ConfiguredJsonValueCodec` is serialized to JSON.
  * `derives JsonData` on a case class provides the JSON path automatically.
  */
trait AgentInput[A]:
  def serialize(a: A): String

object AgentInput:
  given AgentInput[String] with
    def serialize(a: String): String = a

  given [A](using codec: ConfiguredJsonValueCodec[A]): AgentInput[A] with
    def serialize(a: A): String = writeToString(a)
