package orca.backend

import orca.OrcaFlowException
import orca.agents.{BackendTag, SessionId, WireSessionId}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/** A backend's session-durability capability, as ONE structural value rather
  * than three independently-overridable methods.
  *
  * Durability is the whole capability or none of it: a backend either provides
  * [[Durable]] (registry + on-disk/over-the-wire existence probe) or
  * [[Ephemeral]] (registry only, nothing survives the process). There is no way
  * to wire the registry read without the probe, or the probe without the
  * persist hook — the half-wiring that shipped resume bugs in both claude and
  * codex (they overrode one of the old `sessionExists`/`resumeWireId`/
  * `registerSession` trio and left the others at their defaults). Collapsing
  * the three into a single value makes that class of bug unrepresentable.
  *
  * `register` feeds the in-run fresh-vs-resume dispatch for BOTH shapes (an
  * ephemeral backend still resumes within a live process); `persistableWireId`
  * and `exists` are the durability-only operations — `None`/`false` for
  * [[Ephemeral]], since an ephemeral session leaves nothing to persist or probe
  * across a restart.
  */
enum SessionSupport[B <: BackendTag](registry: SessionRegistry[B]):
  /** Sessions live only for the process lifetime (pi's `deleteOnExit` session
    * dirs). The registry still tracks fresh-vs-resume within the run, but
    * nothing is durably resumable, so `persistableWireId` and `exists` always
    * report absence.
    */
  case Ephemeral[B <: BackendTag](r: SessionRegistry[B])
      extends SessionSupport[B](r)

  /** Sessions outlive the process (claude's on-disk transcripts, codex/gemini/
    * opencode's server-side threads). `probe` is the backend's best-effort,
    * non-destructive existence check, run against the resolved wire id (see
    * [[exists]]).
    */
  case Durable[B <: BackendTag](
      r: SessionRegistry[B],
      probe: String => Boolean
  ) extends SessionSupport[B](r)

  /** The fresh-vs-resume decision for `client`, delegated to the registry. The
    * registry itself is encapsulated (backends hold only this
    * [[SessionSupport]]), so a backend's spawn path asks here rather than
    * reaching for a second, possibly-mismatched registry handle.
    */
  final def dispatchFor(client: SessionId[B]): Dispatch[B] =
    registry.dispatchFor(client)

  /** Record the client→wire mapping in the registry, so a follow-up call on the
    * same client id resumes the right thread. On resume the flow runtime also
    * calls this to rehydrate the map from the persisted log. Uniform across
    * both shapes and both registry schemes (see [[SessionRegistry]]).
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
    * THROWING guard: it runs pre-commit, autonomous, and retryable — a fresh
    * attempt may see a healthy init event — so aborting-and-retrying there has
    * different, better failure economics than dropping a finished interactive
    * turn). Both doors live on `SessionSupport`, and the registry is
    * encapsulated, so EVERY resume-mapping write funnels through one of these
    * two — nothing reaches `registry.commitSuccess` directly.
    */
  final def register(client: SessionId[B], server: WireSessionId[B]): Unit =
    if !SessionId.isSafe(WireSessionId.value(server)) then
      SessionSupport.log.error(
        "refusing to record invalid wire id ('{}') for resume; the next call re-seeds",
        WireSessionId.value(server)
      )
    else registry.commitSuccess(client, server)

  /** The THROWING wire-id guard for the autonomous drain (see
    * [[Conversations.drainAndCommit]]): commit the client→wire mapping only
    * after a clean drain, refusing an unsafe id by throwing. Unlike
    * [[register]]'s log-and-skip, this runs pre-commit, autonomous, and
    * retryable — throwing BEFORE `commitSuccess` leaves the registry untouched,
    * so a retry (which may see a healthy init event) needn't unwind a bad
    * commit, and re-seeding a finished-but-unrecorded turn is the wrong
    * trade-off here where nothing has been consumed yet.
    */
  final def commitAfterDrain(
      client: SessionId[B],
      server: WireSessionId[B]
  ): Unit =
    val wire = WireSessionId.value(server)
    if !SessionId.isSafe(wire) then
      throw new OrcaFlowException(
        s"backend reported an invalid session id ('$wire') — refusing to record it for resume"
      )
    registry.commitSuccess(client, server)

  /** The wire id to resume `client` against for the flow runtime to persist
    * into the progress log, or `None` when nothing durable is known.
    * [[Ephemeral]] always returns `None`; [[Durable]] delegates to the
    * registry's `resumeWireId`. Pure, thread-safe, side-effect-free.
    */
  final def persistableWireId(client: SessionId[B]): Option[WireSessionId[B]] =
    this match
      case Ephemeral(_)  => None
      case Durable(_, _) => registry.resumeWireId(client)

  /** Non-destructive, best-effort: does a live, resumable backend conversation
    * exist for `client`? Resolves the wire id via the registry (no mapping ⇒
    * `false`, no probe), then runs the backend `probe` on it, treating ANY
    * non-fatal probe failure as "absent". Must NOT create, mutate, or resume
    * the session; the caller re-seeds on `false`, which is always safe.
    * [[Ephemeral]] always returns `false`.
    *
    * Does NOT re-check [[orca.agents.SessionId.isSafe]] on the resolved wire
    * id: every id that can reach the registry already passed that check at
    * [[register]] or [[commitAfterDrain]] — the only two doors that ever call
    * `registry.commitSuccess` — so a second check here would be provably dead
    * code, not defense in depth.
    */
  final def exists(client: SessionId[B]): Boolean =
    this match
      case Ephemeral(_) => false
      case Durable(_, probe) =>
        registry.resumeWireId(client) match
          case None => false
          case Some(wire) =>
            try probe(WireSessionId.value(wire))
            catch case NonFatal(_) => false

  /** Will the NEXT call on `client` continue an already-live conversation
    * (rather than start a fresh one that must be re-seeded)? This is the
    * question the durable-session runtime actually asks before deciding whether
    * to re-inject the seed + progress preamble — distinct from [[exists]]'s "is
    * there something durably resumable across a restart".
    *
    * [[Durable]] answers it exactly as [[exists]] does (the durable probe). But
    * [[Ephemeral]] differs crucially: an ephemeral backend (pi) keeps NO
    * durable transcript, so `exists` is always `false` — yet within a live
    * process its registry DOES track the in-process claim, and the CLI is
    * genuinely told to `--continue`. So `willContinue` reads the registry's
    * in-process resume mapping (`resumeWireId(client).isDefined`): `false` on
    * the first call, `true` once the session has been committed this run. Using
    * `exists` here instead re-seeds every task of a durable-session loop into a
    * conversation that is, at the protocol level, still continuing.
    */
  final def willContinue(client: SessionId[B]): Boolean =
    this match
      case Ephemeral(_)  => registry.resumeWireId(client).isDefined
      case Durable(_, _) => exists(client)

object SessionSupport:
  private val log = LoggerFactory.getLogger(classOf[SessionSupport[?]])
