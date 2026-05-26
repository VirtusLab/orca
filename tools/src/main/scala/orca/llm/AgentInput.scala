package orca.llm

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString

/** Typeclass that serializes an arbitrary value into the string that gets
  * embedded in the prompt sent to the LLM. Every `run` method on
  * `AutonomousLlmCall` / `InteractiveLlmCall` that accepts an `input: I`
  * requires an `AgentInput[I]` so callers don't have to pre-stringify their
  * arguments.
  *
  * Two givens ship out of the box: `String` passes through verbatim, and any
  * type with a `JsonData` instance is serialized to JSON via its codec.
  * `derives JsonData` on a case class provides the JSON path automatically.
  */
trait AgentInput[A]:
  def serialize(a: A): String

object AgentInput:
  given AgentInput[String] with
    def serialize(a: String): String = a

  given [A](using jd: JsonData[A]): AgentInput[A] with
    def serialize(a: A): String = writeToString(a)(using jd.codec)
