package orca.backend

import orca.OrcaFlowException
import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/** Whether a backend call against a caller-supplied session id starts a fresh
  * session or resumes an existing one.
  *
  * `Resume` carries the `wireId` to put on the wire (already resolved by
  * [[SessionSupport]]). `Fresh`'s claim is `Some(id)` only under
  * [[IdScheme.ClientClaimed]], where the caller-allocated id IS the wire id and
  * the CLI creates the session under it; `None` under
  * [[IdScheme.ServerMinted]], where the server mints its own id at first use.
  * The `Option` stops a server-minting backend from forwarding a fabricated
  * client id onto the wire.
  */
enum Dispatch[B <: BackendTag]:
  case Fresh(claim: Option[WireSessionId[B]])
  case Resume(wireId: WireSessionId[B])

/** How a backend's wire-level session ids come to be — decides what a `Fresh`
  * dispatch may put on the wire and which id a commit records.
  */
enum IdScheme:
  /** The caller-allocated client id IS the wire id: the CLI creates the session
    * under it and resumes against it (claude's `--session-id <uuid>` →
    * `--resume <uuid>`; pi's `--session <id>`).
    */
  case ClientClaimed

  /** The server mints the wire id at first use, learned from the protocol
    * response and mapped to the stable client id (codex/gemini thread ids,
    * opencode's `ses_…`). A fresh session puts nothing on the wire.
    */
  case ServerMinted

/** A backend's whole session capability as one value: [[IdScheme]] says how
  * wire ids come to be, and the presence of a `probe` says whether sessions
  * survive a process restart. Construct via [[SessionSupport.durable]] or
  * [[SessionSupport.ephemeral]].
  *
  * Bundling the client→wire bookkeeping, id scheme, and durability probe into
  * one value keeps the capability whole-or-nothing: no way to wire the resume
  * read without the commit hook, the half-wiring that ships resume bugs.
  *
  * The `dispatchFor` → spawn → `register`/`commitAfterDrain` sequence is NOT
  * atomic, so callers must not share a session id across concurrent calls. The
  * internal map is concurrent because flows fan reviewers out via
  * `mapParUnordered`.
  */
final class SessionSupport[B <: BackendTag] private (
    scheme: IdScheme,
    probe: Option[String => Boolean]
):

  /** client id → wire id, learned at commit. Under [[IdScheme.ClientClaimed]]
    * the stored wire id is the client id itself. Backends commit at whatever
    * protocol point makes the id durable.
    */
  private val wireIds =
    new java.util.concurrent.ConcurrentHashMap[String, String]()

  /** The fresh-vs-resume decision for `client`: `Resume` with the recorded wire
    * id when one is known (committed this run, or rehydrated from the log),
    * otherwise `Fresh` with a claim per the [[IdScheme]].
    */
  def dispatchFor(client: SessionId[B]): Dispatch[B] =
    resumeWire(client) match
      case Some(wire) => Dispatch.Resume(wire)
      case None =>
        scheme match
          case IdScheme.ClientClaimed => Dispatch.Fresh(Some(client.onWire))
          case IdScheme.ServerMinted  => Dispatch.Fresh(None)

  /** Record the client→wire mapping so a follow-up call on the same client id
    * resumes the right thread; also called on resume to rehydrate the map from
    * the persisted log.
    *
    * Log-and-skip wire-id guard for the interactive path and rehydration: an
    * unsafe wire id (empty, or failing [[orca.agents.SessionId.isSafe]]) would
    * make the next call dispatch `resume ""`/`resume ../etc`, so an unsafe id
    * is logged at ERROR and NOTHING is recorded — no throw. The user's
    * completed session output must survive a bookkeeping failure, and
    * rehydration must not hard-abort setup over one stale field; the next call
    * then re-seeds a fresh session. The autonomous drain uses the throwing
    * [[commitAfterDrain]].
    */
  def register(client: SessionId[B], server: WireSessionId[B]): Unit =
    if !SessionId.isSafe(WireSessionId.value(server)) then
      SessionSupport.log.error(
        "refusing to record invalid wire id ('{}') for resume; the next call re-seeds",
        WireSessionId.value(server)
      )
    else commit(client, server)

  /** Throwing wire-id guard for the autonomous drain: commit the client→wire
    * mapping only after a clean drain, refusing an unsafe id by throwing.
    * Throwing before the commit leaves the bookkeeping untouched, so a retry
    * needn't unwind a bad commit — and nothing has been consumed yet, so
    * re-seeding is not warranted.
    */
  def commitAfterDrain(client: SessionId[B], server: WireSessionId[B]): Unit =
    val wire = WireSessionId.value(server)
    if !SessionId.isSafe(wire) then
      throw new OrcaFlowException(
        s"backend reported an invalid session id ('$wire') — refusing to record it for resume"
      )
    commit(client, server)

  /** The first successful commit wins (`putIfAbsent`) — resuming a session
    * never changes its server-side id, so a later commit with a different wire
    * id for the same client is silently dropped. Under
    * [[IdScheme.ClientClaimed]] the stored wire id is the client id itself (the
    * claim), regardless of what the backend reported.
    */
  private def commit(client: SessionId[B], server: WireSessionId[B]): Unit =
    val wire = scheme match
      case IdScheme.ClientClaimed => SessionId.value(client)
      case IdScheme.ServerMinted  => WireSessionId.value(server)
    val _ = wireIds.putIfAbsent(SessionId.value(client), wire)

  /** The wire id recorded for `client`, or `None` when no mapping is known.
    * Every id in the map already passed [[orca.agents.SessionId.isSafe]] at its
    * write door ([[register]] or [[commitAfterDrain]]), so no re-check is
    * needed.
    */
  private def resumeWire(client: SessionId[B]): Option[WireSessionId[B]] =
    Option(wireIds.get(SessionId.value(client))).map(WireSessionId[B](_))

  /** The wire id to persist into the progress log for resuming `client`, or
    * `None` when nothing durable is known — always `None` for an ephemeral
    * backend, whose sessions leave nothing to resume across a restart.
    */
  def persistableWireId(client: SessionId[B]): Option[WireSessionId[B]] =
    if probe.isDefined then resumeWire(client) else None

  /** Will the next call on `client` continue an already-live conversation
    * (rather than start a fresh one that must be re-seeded)? The
    * durable-session runtime asks this before deciding whether to re-inject the
    * seed + progress preamble; re-seeding on `false` is always safe.
    *
    * For a durable backend this resolves the recorded wire id (no mapping ⇒
    * `false`) and runs the `probe` on it: the probe must NOT create, mutate, or
    * resume the session, and any non-fatal failure reads as "won't continue".
    *
    * An ephemeral backend keeps no durable transcript to probe, but its
    * bookkeeping tracks the in-process claim, so the answer is simply whether a
    * mapping is recorded.
    */
  def willContinue(client: SessionId[B]): Boolean =
    probe match
      case None => resumeWire(client).isDefined
      case Some(p) =>
        resumeWire(client) match
          case None => false
          case Some(wire) =>
            try p(WireSessionId.value(wire))
            catch case NonFatal(_) => false

object SessionSupport:
  private val log = LoggerFactory.getLogger(classOf[SessionSupport[?]])

  /** Sessions outlive the process (claude's on-disk transcripts,
    * codex/gemini/opencode's server-side threads). `probe` is a best-effort,
    * non-destructive existence check (see [[SessionSupport.willContinue]]).
    */
  def durable[B <: BackendTag](
      scheme: IdScheme,
      probe: String => Boolean
  ): SessionSupport[B] =
    new SessionSupport(scheme, Some(probe))

  /** Sessions live only for the process lifetime (pi's `deleteOnExit` session
    * dirs). Fresh-vs-resume is tracked within the run, but nothing is durably
    * resumable, so [[SessionSupport.persistableWireId]] always reports absence.
    */
  def ephemeral[B <: BackendTag](scheme: IdScheme): SessionSupport[B] =
    new SessionSupport(scheme, None)
