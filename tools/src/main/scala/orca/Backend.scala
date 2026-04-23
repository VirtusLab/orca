package orca

/** Identifier for a concrete LLM backend. Used as the phantom type parameter
  * on `SessionId[B]`, `LlmResult[B]`, `Conversation[B]`, `LlmTool[B]`, and
  * `LlmBackend[B]` so a session id from one backend can't accidentally flow
  * into another.
  */
enum Backend:
  case ClaudeCode
  case Codex

opaque type SessionId[B <: Backend] = String

object SessionId:
  def apply[B <: Backend](value: String): SessionId[B] = value
  extension [B <: Backend](id: SessionId[B]) def value: String = id
