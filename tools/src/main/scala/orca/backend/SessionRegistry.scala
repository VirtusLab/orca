package orca.backend

import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}

/** Whether a backend call against a given caller-supplied session id should
  * start a fresh session or resume an existing one. Each backend has its own
  * scheme for tracking which is which (claude claims a caller-allocated UUID
  * via `--session-id`; codex maps client UUIDs to server-allocated thread ids),
  * but the call site only cares about the two cases.
  *
  * `Resume` always carries the `wireId` the consumer puts on the wire — the
  * registry has already decided which one it is (claude's client UUID, codex's
  * server thread id). `Fresh` carries an OPTIONAL claim: `Some(id)` only for
  * [[SessionRegistry.ClaimedOnce]], where the caller-allocated id IS the wire
  * id and the CLI is told to create the session under it (claude's
  * `--session-id`); `None` for [[SessionRegistry.ClientToServer]], where the
  * server mints its own id at first use so there is nothing legitimate to put
  * on the wire yet. Making the claim an `Option` (rather than always a real
  * `WireSessionId`) stops a server-minting backend from naively forwarding a
  * fabricated client id onto the wire — the pre-1.1 resume-bug class. Consumers
  * pattern-match on `Fresh` vs `Resume` and forward the id to the CLI.
  */
enum Dispatch[B <: BackendTag]:
  case Fresh(claim: Option[WireSessionId[B]])
  case Resume(wireId: WireSessionId[B])

/** Backend-internal bookkeeping for the fresh-vs-resume decision. Each backend
  * picks one of the impls below (caller-allocated vs server-allocated id
  * schemes); the SPI doesn't constrain when `commitSuccess` fires — backends
  * commit at whatever protocol point makes the id durable (autonomous turns
  * commit after a clean drain via Conversations.drainAndCommit; claude's
  * interactive path commits at spawn; other interactive paths commit after the
  * drive settles).
  *
  * Thread safety: implementations must tolerate concurrent reads/writes since
  * flows fan reviewers out via `mapParUnordered`. The `dispatchFor` → spawn →
  * `commitSuccess` sequence is NOT atomic, so callers must not share a session
  * id across concurrent calls — each reviewer mints its own via
  * `Agent.newSession`.
  */
trait SessionRegistry[B <: BackendTag]:
  def dispatchFor(client: SessionId[B]): Dispatch[B]
  def commitSuccess(client: SessionId[B], server: WireSessionId[B]): Unit

  /** Pure, thread-safe read of the wire id to resume `client` against, or
    * `None` if no live mapping is known. For [[ClientToServer]] this is the
    * recorded server thread id; for [[ClaimedOnce]] the client IS the wire id,
    * so it returns `Some(client)` once claimed and `None` before.
    *
    * Used by the flow runtime to persist the resume wire id into the progress
    * log and to drive the existence probe. Never creates, mutates, or resumes a
    * session.
    */
  def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]]

object SessionRegistry:

  /** For backends whose client-supplied session id IS the canonical id on the
    * wire. The first use of an id is a fresh session; subsequent uses resume
    * it. `commitSuccess` just records that the id has been claimed.
    *
    * Claude's `--session-id <uuid>` (claim) → `--resume <uuid>` (continue)
    * mapping uses this.
    */
  final class ClaimedOnce[B <: BackendTag] extends SessionRegistry[B]:
    private val claimed =
      java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

    def dispatchFor(client: SessionId[B]): Dispatch[B] =
      if claimed.contains(SessionId.value(client)) then
        Dispatch.Resume(client.onWire)
      else Dispatch.Fresh(Some(client.onWire))

    /** The `server` parameter is ignored — for backends using this registry,
      * the wire id IS the client id.
      */
    def commitSuccess(client: SessionId[B], server: WireSessionId[B]): Unit =
      val _ = claimed.add(SessionId.value(client))

    /** The client id IS the wire id, so a claimed client resumes against
      * itself.
      */
    def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]] =
      Option.when(claimed.contains(SessionId.value(client)))(client.onWire)

  /** For backends whose session id is server-minted at first use, learned from
    * the protocol response. The framework hands the caller a stable client id;
    * the backend maps it to whatever the wire id turns out to be and resumes
    * against that.
    *
    * `commitSuccess` uses `putIfAbsent` — the first successful call wins. A
    * subsequent commit with a different server id for the same client is
    * silently dropped; this matches the protocol invariant that resuming a
    * session never changes its server-side id.
    *
    * Codex's `codex exec` (mints) → `codex exec resume <server-id>` (continue)
    * mapping uses this.
    */
  final class ClientToServer[B <: BackendTag] extends SessionRegistry[B]:
    private val map =
      new java.util.concurrent.ConcurrentHashMap[String, String]()

    def dispatchFor(client: SessionId[B]): Dispatch[B] =
      Option(map.get(SessionId.value(client))) match
        case Some(serverId) => Dispatch.Resume(WireSessionId[B](serverId))
        // No client id goes on the wire: the server mints its own id at first
        // use, so there is nothing legitimate to claim yet (never `onWire`).
        case None => Dispatch.Fresh(None)

    def commitSuccess(client: SessionId[B], server: WireSessionId[B]): Unit =
      val _ = map.putIfAbsent(
        SessionId.value(client),
        WireSessionId.value(server)
      )

    def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]] =
      Option(map.get(SessionId.value(client))).map(WireSessionId[B](_))
