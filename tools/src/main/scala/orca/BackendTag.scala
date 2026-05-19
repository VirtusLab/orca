package orca

/** Type tag for a concrete LLM backend. Carried as the `B` parameter on
  * [[SessionId]], [[orca.backend.LlmResult]], [[orca.backend.Conversation]],
  * [[LlmTool]], and [[orca.backend.LlmBackend]] so a session id from one
  * backend can't accidentally flow into another. Distinct from
  * [[orca.backend.LlmBackend]], which is the runtime SPI; this enum is the
  * compile-time discriminator.
  */
enum BackendTag:
  case ClaudeCode
  case Codex

opaque type SessionId[B <: BackendTag] = String

object SessionId:
  def apply[B <: BackendTag](value: String): SessionId[B] = value
  extension [B <: BackendTag](id: SessionId[B]) def value: String = id
