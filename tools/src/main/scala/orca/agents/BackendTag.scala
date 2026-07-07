package orca.agents

/** Type tag for a concrete LLM backend. Carried as the `B` parameter on
  * [[SessionId]], [[orca.backend.AgentResult]], [[orca.backend.Conversation]],
  * [[Agent]], and [[orca.backend.AgentBackend]] so a session id from one
  * backend can't accidentally flow into another. Distinct from
  * [[orca.backend.AgentBackend]], which is the runtime SPI; this enum is the
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

  /** Returns `true` iff `id` is a well-formed session id that is safe to embed
    * in a file path, regex, or URL without further escaping. Accepted: 1–200
    * characters from `[A-Za-z0-9_-]`, covering all legitimate ids (UUIDs for
    * claude/codex/gemini; `ses_…` for opencode). Rejects `.`, `/`, `*`, `[`,
    * `?`, `#`, `..` and every other character that could enable path traversal,
    * regex injection, or URL injection.
    *
    * Every `sessionExists` override calls this before using the id.
    */
  def isSafe(id: String): Boolean =
    id.nonEmpty && id.length <= 200 && id.matches("[A-Za-z0-9_-]+")

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

/** The id a backend actually resumes a conversation against on the wire —
  * distinct from [[SessionId]], orca's stable client handle. For claude (and
  * pi's claimed ids) the two coincide; for codex/gemini/opencode the wire id is
  * a server-minted thread id learned from the protocol. A separate opaque type
  * makes returning a wire id as the caller's handle — or resuming against a
  * client id — a compile error (the bug class behind two shipped resume bugs;
  * see complexity review finding 1.1).
  */
opaque type WireSessionId[B <: BackendTag] = String

object WireSessionId:
  def apply[B <: BackendTag](value: String): WireSessionId[B] = value
  extension [B <: BackendTag](id: WireSessionId[B]) def value: String = id

extension [B <: BackendTag](id: SessionId[B])
  /** The client id used verbatim on the wire — the one legitimate crossing
    * between the two id spaces, for registries where the caller-allocated id IS
    * the wire id ([[orca.backend.SessionRegistry.ClaimedOnce]]).
    */
  def onWire: WireSessionId[B] = id
