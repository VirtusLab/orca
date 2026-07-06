package orca.backend

import orca.events.OrcaListener
import orca.agents.{
  BackendTag,
  AgentConfig,
  SessionId,
  WireSessionId,
  isSafeSessionId
}

import ox.Ox

import scala.util.control.NonFatal

/** SPI implemented per backend (Claude, Codex, …). The framework calls these
  * methods from the autonomous-text and structured-output paths
  * ([[AutonomousTextCall]], [[AgentCall]]).
  *
  * Each method takes a `session: SessionId[B]` — the framework hands the same
  * value across calls; the backend decides internally whether this is the first
  * invocation (and the session needs creating) or a continuation. Two methods
  * cover the UX shape: `runAutonomous` runs to completion off-screen and
  * returns the result; `runInteractive` returns a live [[Conversation]] the
  * caller drives through an [[Interaction]].
  *
  * `prompt` on every method is the full wire-level message sent to the agent —
  * with whatever template scaffolding, schema, and rules the caller wrapped
  * around the user's input. `displayPrompt` (interactive only) is what the
  * renderer shows the user; autonomous has no renderer, hence no
  * `displayPrompt`.
  *
  * `workDir` is the working directory the agent subprocess sees.
  */
trait AgentBackend[B <: BackendTag]:
  /** Run one autonomous turn against `session` and return its result. The
    * backend decides whether to create the session (first call with this id) or
    * resume it (subsequent calls).
    *
    * `events` receives per-tool-use and per-message progress as the subprocess
    * runs, so the user has something to watch while the agent works. Defaults
    * to a no-op listener for callers (typically tests) that don't observe
    * progress.
    *
    * `outputSchema`, when supplied, is the JSON Schema the final assistant
    * payload must conform to. Backends that enforce schemas natively (claude's
    * `--json-schema`) pass it to the CLI; backends that don't can ignore it.
    * Either way the schema is forwarded to the conversation so the autonomous
    * drain can recognise "the agent's last message IS the structured payload"
    * and suppress the raw JSON from the user log — the caller surfaces it via
    * `OrcaEvent.StructuredResult` instead.
    */
  def runAutonomous(
      prompt: String,
      session: SessionId[B],
      config: AgentConfig,
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop,
      outputSchema: Option[String] = None
  ): AgentResult[B]

  /** Launch an interactive session against `session` and return a live
    * [[Conversation]] the caller hands to [[Interaction.drive]] for rendering
    * and user steering. The backend owns the subprocess and event parsing; the
    * channel owns UX.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must conform to,
    * or `None` for free-form text. Backends that support structured-output
    * validation (claude's `--json-schema`) enforce it; those that don't can
    * ignore the parameter and let the caller validate post-hoc.
    */
  def runInteractive(
      prompt: String,
      session: SessionId[B],
      displayPrompt: String,
      config: AgentConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  )(using Ox): Conversation[B]

  /** Hook for backends that mint server-side session ids during a conversation
    * drain: after the interactive `Conversation` returned by [[runInteractive]]
    * settles, the framework calls this with the client session id it dispatched
    * on and the server id learned from the result. Backends with
    * caller-supplied ids (claude — `--session-id <uuid>`) can leave the default
    * no-op. Codex overrides to record the client→server mapping so a follow-up
    * `runAutonomous` / `runInteractive` on the same client id resumes the right
    * thread.
    */
  def registerSession(client: SessionId[B], server: WireSessionId[B]): Unit =
    ()

  /** Non-destructive check: does a live, resumable backend conversation exist
    * for `session`? Best-effort — returns `false` when the backend store/CLI is
    * absent or the answer can't be determined (the caller then re-seeds, which
    * is always safe). Must NOT create, mutate, or resume the session.
    */
  def sessionExists(session: SessionId[B]): Boolean = false

  /** Read the wire id to resume `client` against, or `None` if no live mapping
    * is known (or the backend's sessions aren't durably resumable). Pure,
    * thread-safe, side-effect-free.
    *
    * Backends with a durable [[SessionRegistry]] delegate to its
    * `resumeWireId`; the default returns `None` (pi, whose temp-dir sessions
    * don't survive a restart, keeps the default). Used by the flow runtime to
    * persist the resume wire id into the progress log (so a resumed run can
    * rehydrate it via [[registerSession]]) and to probe it for existence.
    */
  def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]] = None

  /** Run `probe` on `id` only if `id` is a safe session id, treating ANY
    * non-fatal failure (and an unsafe id) as "not found". The non-destructive,
    * best-effort contract every `sessionExists` probe shares.
    */
  protected def probeGuarded(id: String)(probe: String => Boolean): Boolean =
    if !isSafeSessionId(id) then false
    else
      try probe(id)
      catch case NonFatal(_) => false

  /** For server-id backends: resolve the recorded resume wire id via `registry`
    * (None ⇒ not found, no probe) and `probeGuarded` it.
    */
  protected def probeServerSession(
      session: SessionId[B],
      registry: SessionRegistry[B]
  )(probe: String => Boolean): Boolean =
    registry.resumeWireId(session) match
      case None      => false
      case Some(srv) => probeGuarded(srv.value)(probe)
