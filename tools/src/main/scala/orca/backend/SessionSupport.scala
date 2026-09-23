package orca.backend

import orca.AgentTurnFailed
import orca.events.TurnDebit
import orca.agents.{BackendTag, SessionId, TurnDispatch, WireSessionId, onWire}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/** What the next turn against a client id does with the backend's conversation
  * — the one answer both the prompt side (re-seed or not) and the wire side
  * (argv) act on. Obtain it from [[SessionSupport.dispatchFor]].
  *
  * `Fresh` opens a conversation, so whatever context the turn needs must be
  * sent again. Its claim is `Some(id)` only under [[IdScheme.ClientClaimed]],
  * where the caller-allocated id IS the wire id and the CLI creates the session
  * under it; `None` under [[IdScheme.ServerMinted]], where the server mints its
  * own id at first use. The `Option` stops a server-minting backend from
  * forwarding a fabricated client id onto the wire.
  *
  * `Resume` continues the conversation the backend holds under `wireId`;
  * `origin` says whether that conversation predates this run.
  */
enum Dispatch[B <: BackendTag]:
  case Fresh(claim: Option[WireSessionId[B]])
  case Resume(wireId: WireSessionId[B], origin: ResumeOrigin)

  /** As [[AgentBackend.enforcementCell]] takes it — wire ids dropped. */
  def asTurnDispatch: TurnDispatch = this match
    case Dispatch.Fresh(_)     => TurnDispatch.Fresh
    case Dispatch.Resume(_, _) => TurnDispatch.Resumed

/** Which run opened the conversation a [[Dispatch.Resume]] continues. */
enum ResumeOrigin:
  /** A turn of this run committed it. */
  case ThisRun

  /** A previous run opened it: its wire id was rehydrated from the session
    * store, or — under [[IdScheme.ClientClaimed]], with nothing recorded — the
    * backend already holds the client's own claim, which only a run interrupted
    * during the session's first turn leaves behind.
    */
  case EarlierRun

/** How a backend's wire-level session ids come to be — decides what a `Fresh`
  * dispatch may put on the wire and which id a commit records.
  */
enum IdScheme:
  /** The caller-allocated client id IS the wire id: the CLI creates the session
    * under it and resumes against it (claude's `--session-id <uuid>` →
    * `--resume <uuid>`; pi's per-id `--session-dir` → `--continue`).
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

  /** client id → what is known about its wire id. Under
    * [[IdScheme.ClientClaimed]] the stored wire id is the client id itself.
    */
  private val entries =
    new java.util.concurrent.ConcurrentHashMap[String, SessionSupport.Entry[
      B
    ]]()

  /** What the next turn against `client` does — see [[Dispatch]].
    *
    * A resumable answer is settled on first ask: a rehydrated wire id is probed
    * once, then every later ask this run reads the stored answer, so a turn
    * whose prompt continues a conversation spawns against it too. A `Fresh`
    * answer is not stored — a failed first turn can leave a claim the backend
    * holds, which the next ask must see — so under [[IdScheme.ClientClaimed]]
    * each ask re-runs the claim probe.
    *
    * For a durable backend the `probe` must NOT create, mutate, or resume the
    * session. An ephemeral backend keeps no durable transcript to probe, so its
    * recorded mapping alone answers `Resume`.
    */
  def dispatchFor(client: SessionId[B]): Dispatch[B] =
    val key = SessionId.value(client)
    Option(entries.get(key)) match
      case Some(SessionSupport.Entry.Settled(wire, origin)) =>
        Dispatch.Resume(wire, origin)
      case Some(rehydrated @ SessionSupport.Entry.Rehydrated(wire)) =>
        if probe.forall(holds(_, WireSessionId.value(wire))) then
          val _ = entries.replace(key, rehydrated, settledEarlierRun(wire))
          Dispatch.Resume(wire, ResumeOrigin.EarlierRun)
        else
          // Dropped so this run's next commit (`putIfAbsent`) records the
          // conversation the re-seeded turn opens, not the lost one.
          val _ = entries.remove(key, rehydrated)
          fresh(client)
      case None =>
        heldClaim(client) match
          case Some(claim) =>
            val _ = entries.putIfAbsent(key, settledEarlierRun(claim))
            Dispatch.Resume(claim, ResumeOrigin.EarlierRun)
          case None => fresh(client)

  private def settledEarlierRun(
      wire: WireSessionId[B]
  ): SessionSupport.Entry[B] =
    SessionSupport.Entry.Settled(wire, ResumeOrigin.EarlierRun)

  private def fresh(client: SessionId[B]): Dispatch[B] = scheme match
    case IdScheme.ClientClaimed => Dispatch.Fresh(Some(client.onWire))
    case IdScheme.ServerMinted  => Dispatch.Fresh(None)

  /** Record the wire id a previous run persisted for `client`, unconfirmed
    * until the next [[dispatchFor]] probes it. `agent.session(name, seed)`
    * calls this when it reuses a recorded session.
    *
    * An unsafe wire id (empty, or failing [[orca.agents.SessionId.isSafe]]) is
    * logged at ERROR and NOT recorded — one stale field must not fail the run;
    * the next call re-seeds a fresh session.
    */
  def rehydrate(client: SessionId[B], wire: WireSessionId[B]): Unit =
    if isRecordable(wire) then
      val _ = entries.putIfAbsent(
        SessionId.value(client),
        SessionSupport.Entry.Rehydrated(wire)
      )

  /** Record the client→wire mapping after an interactive turn, so a follow-up
    * call on the same client id resumes the right thread.
    *
    * Log-and-skip wire-id guard: an unsafe wire id would make the next call
    * dispatch `resume ""`/`resume ../etc`, so it is logged at ERROR and NOTHING
    * is recorded — no throw. The user's completed session output must survive a
    * bookkeeping failure; the next call then re-seeds a fresh session. The
    * autonomous drain uses the throwing [[commitAfterDrain]].
    */
  def register(client: SessionId[B], server: WireSessionId[B]): Unit =
    if isRecordable(server) then commit(client, server)

  private def isRecordable(wire: WireSessionId[B]): Boolean =
    val safe = SessionId.isSafe(WireSessionId.value(wire))
    if !safe then
      SessionSupport.log.error(
        "refusing to record invalid wire id ('{}') for resume; the next call re-seeds",
        WireSessionId.value(wire)
      )
    safe

  /** Throwing wire-id guard for the autonomous drain: commit the client→wire
    * mapping only after a clean drain, refusing an unsafe id with
    * [[AgentTurnFailed]]. The turn has run by then, so a retry would redo its
    * work; the failure carries the turn's usage instead. Throwing before the
    * commit leaves the bookkeeping untouched.
    */
  def commitAfterDrain(client: SessionId[B], result: AgentResult[B]): Unit =
    val wire = WireSessionId.value(result.wireId)
    if !SessionId.isSafe(wire) then
      throw new AgentTurnFailed(
        s"backend reported an invalid session id ('$wire') — refusing to record it for resume",
        TurnDebit.Observed(result.usage, result.model)
      )
    commit(client, result.wireId)

  /** The first recorded entry wins (`putIfAbsent`) — resuming a session never
    * changes its server-side id, so a later commit with a different wire id for
    * the same client is silently dropped. Under [[IdScheme.ClientClaimed]] the
    * stored wire id is the client id itself (the claim), regardless of what the
    * backend reported.
    */
  private def commit(client: SessionId[B], server: WireSessionId[B]): Unit =
    val wire = scheme match
      case IdScheme.ClientClaimed => client.onWire
      case IdScheme.ServerMinted  => server
    val _ = entries.putIfAbsent(
      SessionId.value(client),
      SessionSupport.Entry.Settled(wire, ResumeOrigin.ThisRun)
    )

  /** Under [[IdScheme.ClientClaimed]] the client id IS the wire id, so a
    * conversation the backend already holds under it resumes with nothing
    * recorded — the state a run interrupted during a session's first turn
    * leaves behind, having written the transcript but never reached its commit.
    * Claiming that id a second time is what those CLIs refuse, so a `Fresh`
    * dispatch here would fail the run.
    *
    * A server-minting backend puts no client id on the wire, so it has nothing
    * to ask about; an ephemeral one has no probe and keeps nothing across a
    * process.
    *
    * [[orca.agents.SessionId.isSafe]] gates the probe as it gates the recorded
    * map's write doors: a client id read back from the session store reaches a
    * probe that builds a path, a regex or a URL from it.
    */
  private def heldClaim(client: SessionId[B]): Option[WireSessionId[B]] =
    val id = SessionId.value(client)
    scheme match
      case IdScheme.ServerMinted => None
      case IdScheme.ClientClaimed =>
        Option.when(SessionId.isSafe(id) && probe.exists(holds(_, id)))(
          client.onWire
        )

  /** Runs `p` against `wireId`, reading a non-fatal failure as "gone": a probe
    * must not fail a turn, and opening a fresh conversation is always safe.
    */
  private def holds(p: String => Boolean, wireId: String): Boolean =
    try p(wireId)
    catch case NonFatal(_) => false

  /** The wire id to persist into the progress log for resuming `client`, or
    * `None` when nothing durable is known — always `None` for an ephemeral
    * backend, whose sessions leave nothing to resume across a restart. A held
    * claim is known only once a [[dispatchFor]] has asked.
    */
  def persistableWireId(client: SessionId[B]): Option[WireSessionId[B]] =
    if probe.isDefined then
      Option(entries.get(SessionId.value(client))).map(_.wire)
    else None

  /** The one identity this conversation is known by across events — see
    * [[orca.events.OrcaEvent.conversationKey]]. Lives next to the client→wire
    * map so every emitter derives it the same way; a second derivation
    * elsewhere is how a turn stops joining to the session that produced it.
    *
    * Resolve it per turn, not once per call: the wire id is minted during the
    * first turn, so a value taken before that names the session by its client
    * id only.
    */
  def conversationKey(client: SessionId[B]): String =
    orca.events.OrcaEvent.conversationKey(
      SessionId.value(client),
      persistableWireId(client).map(WireSessionId.value)
    )

object SessionSupport:
  private val log = LoggerFactory.getLogger(classOf[SessionSupport[?]])

  /** What [[SessionSupport]] knows about one client's wire id. */
  private enum Entry[B <: BackendTag]:
    /** Read back from the session store; not yet probed this run. */
    case Rehydrated(wire: WireSessionId[B])

    /** Committed by this run, or confirmed live by a probe. */
    case Settled(wire: WireSessionId[B], origin: ResumeOrigin)

    def wire: WireSessionId[B]

  /** Sessions outlive the process (claude's on-disk transcripts,
    * codex/gemini/opencode's server-side threads). `probe` is a best-effort,
    * non-destructive existence check (see [[SessionSupport.dispatchFor]]).
    */
  def durable[B <: BackendTag](
      scheme: IdScheme,
      probe: String => Boolean
  ): SessionSupport[B] =
    new SessionSupport(scheme, Some(probe))

  /** Sessions live only for the process lifetime — no on-disk transcript or
    * server-side thread survives it. Fresh-vs-resume is tracked within the run,
    * but nothing is durably resumable, so [[SessionSupport.persistableWireId]]
    * always reports absence.
    */
  def ephemeral[B <: BackendTag](scheme: IdScheme): SessionSupport[B] =
    new SessionSupport(scheme, None)
