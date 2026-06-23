package orca.llm

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
  case Opencode
  case Pi
  case Gemini

opaque type SessionId[B <: BackendTag] = String

object SessionId:
  def apply[B <: BackendTag](value: String): SessionId[B] = value
  extension [B <: BackendTag](id: SessionId[B]) def value: String = id

  /** Mint a fresh session id (random UUID). The id is client-allocated — the
    * library uses it as a pre-shared key with the backend on the first call.
    * For claude, this maps to `--session-id <uuid>`; for codex (which doesn't
    * accept caller-supplied ids), the backend stores a client→server mapping
    * keyed on this value.
    */
  def fresh[B <: BackendTag]: SessionId[B] =
    java.util.UUID.randomUUID.toString

  /** Tag-erased session id for heterogeneous maps where the backend tag varies
    * per entry (and Scala 3 can't reduce `SessionId[?]` outside this file).
    * Convert with `Untyped.from(sid)` / `untyped.as[B]`; both are zero-cost
    * because `Untyped` and `SessionId[B]` share `String` as the runtime
    * representation. The `as[B]` recovery is type-level only — callers must
    * maintain the invariant that a given entry's `B` matches what was written.
    */
  opaque type Untyped = String
  object Untyped:
    def from[B <: BackendTag](sid: SessionId[B]): Untyped = sid
    extension (u: Untyped) def as[B <: BackendTag]: SessionId[B] = u

  /** `SessionId[B]` is an opaque alias over `String`; within this file the
    * alias is transparent, so we can delegate to the `JsonData[String]`
    * instance directly. Referencing it by its synthesized name avoids the
    * infinite-loop the compiler detects when `summon[JsonData[String]]` sees
    * this given as a candidate (since `SessionId[B] = String` inside the
    * opaque-alias file). A session id is a plain JSON string on the wire — no
    * wrapping, lossless round-trip.
    */
  given [B <: BackendTag]: JsonData[SessionId[B]] =
    JsonData.given_JsonData_String
