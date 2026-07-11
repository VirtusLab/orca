package orca.backend

import orca.OrcaFlowException
import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/** Whether a backend call against a given caller-supplied session id should
  * start a fresh session or resume an existing one. Each backend has its own id
  * scheme (see [[IdScheme]]), but the call site only cares about the two cases.
  *
  * `Resume` always carries the `wireId` the consumer puts on the wire —
  * [[SessionSupport]] has already decided which one it is (claude's client
  * UUID, codex's server thread id). `Fresh` carries an OPTIONAL claim:
  * `Some(id)` only under [[IdScheme.ClientClaimed]], where the caller-allocated
  * id IS the wire id and the CLI is told to create the session under it
  * (claude's `--session-id`); `None` under [[IdScheme.ServerMinted]], where the
  * server mints its own id at first use so there is nothing legitimate to put
  * on the wire yet. Making the claim an `Option` (rather than always a real
  * `WireSessionId`) stops a server-minting backend from naively forwarding a
  * fabricated client id onto the wire. Consumers pattern-match on `Fresh` vs
  * `Resume` and forward the id to the CLI.
  */
enum Dispatch[B <: BackendTag]:
  case Fresh(claim: Option[WireSessionId[B]])
  case Resume(wireId: WireSessionId[B])

/** How a backend's wire-level session ids come to be — the axis that decides
  * what a `Fresh` dispatch may put on the wire and which id a commit records.
  */
enum IdScheme:
  /** The caller-allocated client id IS the wire id: the CLI is told to create
    * the session under it and resumes against it (claude's `--session-id
    * <uuid>` → `--resume <uuid>`; pi's `--session <id>`).
    */
  case ClientClaimed

  /** The server mints the wire id at first use; it is learned from the protocol
    * response and mapped to the stable client id (codex/gemini thread ids,
    * opencode's `ses_…`). A fresh session puts nothing on the wire.
    */
  case ServerMinted

/** A backend's whole session capability as ONE value: [[IdScheme]] says how
  * wire ids come to be, and the presence of a `probe` says whether sessions
  * survive a process restart. Construct via [[SessionSupport.durable]]
  * (sessions outlive the process: claude's on-disk transcripts,
  * codex/gemini/opencode's server-side threads) or [[SessionSupport.ephemeral]]
  * (nothing survives the process: pi's `deleteOnExit` session dirs).
  *
  * Bundling the client→wire bookkeeping, the id scheme, and the durability
  * probe into a single value keeps the capability whole-or-nothing: there is no
  * way to wire the resume read without the commit hook, or the probe without
  * either — the half-wiring that ships resume bugs. The bookkeeping is
  * internal; every consumer goes through the methods below.
  *
  * The `dispatchFor` → spawn → `register`/`commitAfterDrain` sequence is NOT
  * atomic, so callers must not share a session id across concurrent calls —
  * each reviewer mints its own via `Agent.newSession`. The internal map is
  * concurrent because flows fan reviewers out via `mapParUnordered`.
  */
final class SessionSupport[B <: BackendTag] private (
    scheme: IdScheme,
    probe: Option[String => Boolean]
):

  /** client id → wire id, learned at commit. Under [[IdScheme.ClientClaimed]]
    * the stored wire id is the client id itself. Backends commit at whatever
    * protocol point makes the id durable (autonomous turns commit after a clean
    * drain via `Conversations.drainAndCommit`; claude's interactive path
    * commits at spawn; other interactive paths commit after the drive settles).
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

  /** Record the client→wire mapping, so a follow-up call on the same client id
    * resumes the right thread. On resume the flow runtime also calls this to
    * rehydrate the map from the persisted log.
    *
    * The LOG-AND-SKIP wire-id guard, for the interactive path
    * (`AgentCall.runInteractiveOnce`) and rehydration
    * (`Agent.registerResumeWireId`). An unsafe wire id (empty, or failing
    * [[orca.agents.SessionId.isSafe]]) would make the NEXT call dispatch
    * `resume ""`/`resume ../etc`; a poisoned log would rehydrate it. So if the
    * id is unsafe we LOG at ERROR and record NOTHING — we do NOT throw. On the
    * interactive path the user's completed session output must survive a
    * bookkeeping failure, and rehydration must not hard-abort setup over one
    * stale field; the safe fallback is that the next call re-seeds a fresh
    * session.
    *
    * The autonomous drain uses the sibling [[commitAfterDrain]] instead (a
    * THROWING guard). Both doors live here and the bookkeeping is encapsulated,
    * so EVERY resume-mapping write funnels through one of the two.
    */
  def register(client: SessionId[B], server: WireSessionId[B]): Unit =
    if !SessionId.isSafe(WireSessionId.value(server)) then
      SessionSupport.log.error(
        "refusing to record invalid wire id ('{}') for resume; the next call re-seeds",
        WireSessionId.value(server)
      )
    else commit(client, server)

  /** The THROWING wire-id guard for the autonomous drain (see
    * [[Conversations.drainAndCommit]]): commit the client→wire mapping only
    * after a clean drain, refusing an unsafe id by throwing. Unlike
    * [[register]]'s log-and-skip, this runs pre-commit, autonomous, and
    * retryable — throwing BEFORE the commit leaves the bookkeeping untouched,
    * so a retry (which may see a healthy init event) needn't unwind a bad
    * commit, and re-seeding a finished-but-unrecorded turn is the wrong
    * trade-off here where nothing has been consumed yet.
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

  /** Pure, thread-safe read of the wire id recorded for `client`, or `None`
    * when no mapping is known. Every id in the map already passed
    * [[orca.agents.SessionId.isSafe]] at [[register]] or [[commitAfterDrain]] —
    * the only two write doors — so no re-check is needed downstream.
    */
  private def resumeWire(client: SessionId[B]): Option[WireSessionId[B]] =
    Option(wireIds.get(SessionId.value(client))).map(WireSessionId[B](_))

  /** The wire id to resume `client` against for the flow runtime to persist
    * into the progress log, or `None` when nothing durable is known — always
    * `None` for an ephemeral backend, since its sessions leave nothing to
    * resume across a restart. Pure, thread-safe, side-effect-free.
    */
  def persistableWireId(client: SessionId[B]): Option[WireSessionId[B]] =
    if probe.isDefined then resumeWire(client) else None

  /** Will the NEXT call on `client` continue an already-live conversation
    * (rather than start a fresh one that must be re-seeded)? This is the
    * question the durable-session runtime asks before deciding whether to
    * re-inject the seed + progress preamble.
    *
    * For a durable backend this resolves the recorded wire id (no mapping ⇒
    * `false`) and runs the `probe` on it — best-effort and non-destructive: the
    * probe must NOT create, mutate, or resume the session, ANY non-fatal probe
    * failure reads as "won't continue", and the caller re-seeds on `false`,
    * which is always safe.
    *
    * An ephemeral backend (pi) differs crucially: it keeps no durable
    * transcript to probe — yet within a live process its bookkeeping DOES track
    * the in-process claim, and the CLI is genuinely told to continue. So the
    * answer is simply whether a mapping is recorded: `false` on the first call,
    * `true` once the session has been committed this run.
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
    * codex/gemini/opencode's server-side threads). `probe` is the backend's
    * best-effort, non-destructive existence check, run against the resolved
    * wire id (see [[SessionSupport.willContinue]]).
    */
  def durable[B <: BackendTag](
      scheme: IdScheme,
      probe: String => Boolean
  ): SessionSupport[B] =
    new SessionSupport(scheme, Some(probe))

  /** Sessions live only for the process lifetime (pi's `deleteOnExit` session
    * dirs). Fresh-vs-resume is still tracked within the run, but nothing is
    * durably resumable, so [[SessionSupport.persistableWireId]] always reports
    * absence.
    */
  def ephemeral[B <: BackendTag](scheme: IdScheme): SessionSupport[B] =
    new SessionSupport(scheme, None)
