package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}

/** Compile-time type tag for a concrete LLM backend. Carried as the `B`
  * parameter on [[SessionId]], [[orca.backend.AgentResult]],
  * [[orca.backend.LiveTurn]], [[Agent]], and [[orca.backend.AgentBackend]] so a
  * session id from one backend can't accidentally flow into another. Distinct
  * from the runtime SPI [[orca.backend.AgentBackend]].
  *
  * Persisted (session records, attempt manifests) and emitted as its case name.
  */
enum BackendTag:
  case ClaudeCode, Codex, Opencode, Pi, Gemini

object BackendTag:
  given codec: ConfiguredJsonValueCodec[BackendTag] =
    ConfiguredJsonValueCodec.derived[BackendTag](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )

/** Orca's stable client handle for one agent conversation. Library-internal:
  * flow scripts hold a conversation through a `Chat` or `orca.FlowSession`,
  * which bundle the id with the agent that runs it.
  */
private[orca] opaque type SessionId[B <: BackendTag] = String

private[orca] object SessionId:
  /** The raw, UNCHECKED constructor — `private[orca]` because a string sourced
    * outside the library must go through [[parse]] instead. Internal trusted
    * callers ([[fresh]], [[onWire]], the write-policy sites) reach it directly.
    */
  private[orca] def apply[B <: BackendTag](value: String): SessionId[B] = value
  extension [B <: BackendTag](id: SessionId[B]) def value: String = id

  /** Returns `true` iff `id` is safe to embed in a file path, regex, or URL
    * without further escaping: 1–200 characters from `[A-Za-z0-9_-]`, covering
    * all legitimate ids (UUIDs for claude/codex/gemini; `ses_…` for opencode).
    * Rejects `.`, `/`, `*`, `[`, `?`, `#`, `..` and anything else that could
    * enable path traversal, regex injection, or URL injection.
    *
    * The predicate behind [[parse]] and the write-policy guards
    * ([[orca.backend.SessionSupport.register]] /
    * [[orca.backend.SessionSupport.commitAfterDrain]]); downstream re-checks
    * are redundant since nothing past those doors can hold an id that failed
    * here.
    */
  def isSafe(id: String): Boolean =
    id.nonEmpty && id.length <= 200 && id.matches("[A-Za-z0-9_-]+")

  /** The validated door for a log/wire-sourced string: `Some` iff it passes
    * [[isSafe]]. Use this — not the private raw constructor — for any
    * `SessionId` built from a string the library didn't itself mint (a
    * persisted `SessionRecord.id`, primarily). Callers that get `None` should
    * skip the record with a visible warning rather than guess (see
    * `Session.session`'s reuse arm).
    */
  def parse[B <: BackendTag](value: String): Option[SessionId[B]] =
    Option.when(isSafe(value))(value)

  /** Mint a fresh session id (random UUID). Client-allocated — used as a
    * pre-shared key with the backend on the first call. For claude this maps to
    * `--session-id <uuid>`; for codex (which rejects caller-supplied ids) the
    * backend stores a client→server mapping keyed on this value.
    */
  def fresh[B <: BackendTag]: SessionId[B] =
    java.util.UUID.randomUUID.toString

/** The id a backend actually resumes a conversation against on the wire —
  * distinct from [[SessionId]], orca's stable client handle. For claude (and
  * pi's claimed ids) the two coincide; for codex/gemini/opencode the wire id is
  * a server-minted thread id learned from the protocol. A separate opaque type
  * makes returning a wire id as the caller's handle — or resuming against a
  * client id — a compile error.
  */
private[orca] opaque type WireSessionId[B <: BackendTag] = String

private[orca] object WireSessionId:
  /** The raw, UNCHECKED constructor — `private[orca]` for the same reason as
    * [[SessionId.apply]]: a log/wire-sourced string must go through [[parse]].
    * Internal trusted callers (backends building a wire id from a live protocol
    * response, [[SessionId.onWire]], the write-policy sites) reach it directly.
    */
  private[orca] def apply[B <: BackendTag](value: String): WireSessionId[B] =
    value
  extension [B <: BackendTag](id: WireSessionId[B]) def value: String = id

  /** The validated door for a wire-id string read back from disk (a persisted
    * `SessionRecord.resumeWireId` in `orca.sessions`): `Some` iff it passes
    * [[SessionId.isSafe]]. See [[SessionId.parse]] for the sibling on the
    * client-id side.
    */
  def parse[B <: BackendTag](value: String): Option[WireSessionId[B]] =
    Option.when(SessionId.isSafe(value))(value)

extension [B <: BackendTag](id: SessionId[B])
  /** The client id used verbatim on the wire — the one legitimate crossing
    * between the two id spaces, for backends where the caller-allocated id IS
    * the wire id ([[orca.backend.IdScheme.ClientClaimed]]).
    */
  def onWire: WireSessionId[B] = id
