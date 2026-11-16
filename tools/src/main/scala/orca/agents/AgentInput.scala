package orca.agents

import orca.json.{JsonCodec, JsonData}

/** Serializes an arbitrary value into the string embedded in the prompt sent to
  * the LLM, so callers don't have to pre-stringify their arguments.
  *
  * Two givens ship out of the box: `String` passes through verbatim, and any
  * type with a `JsonData` instance is serialized to JSON via its codec.
  */
trait AgentInput[A]:
  def serialize(a: A): String

object AgentInput:
  given AgentInput[String] with
    def serialize(a: String): String = a

  given [A](using jd: JsonData[A]): AgentInput[A] with
    def serialize(a: A): String = JsonCodec.writeString(a)(using jd.codec)
