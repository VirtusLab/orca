package orca.backend

import orca.OrcaFlowException
import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}

class SessionSupportTest extends munit.FunSuite:

  private def clientSid(s: String): SessionId[BackendTag.Codex.type] =
    SessionId[BackendTag.Codex.type](s)

  private def wireSid(s: String): WireSessionId[BackendTag.Codex.type] =
    WireSessionId[BackendTag.Codex.type](s)

  // ── dispatch per id scheme ─────────────────────────────────────────────────

  test("ClientClaimed: dispatchFor flips Fresh(claim) → Resume after commit"):
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => false
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("client-A")
    assertEquals(s.dispatchFor(client), Dispatch.Fresh(Some(client.onWire)))
    s.register(client, client.onWire)
    assertEquals(s.dispatchFor(client), Dispatch.Resume(client.onWire))

  test("ClientClaimed: distinct client ids are tracked independently"):
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => false
    )
    val a = SessionId[BackendTag.ClaudeCode.type]("a")
    val b = SessionId[BackendTag.ClaudeCode.type]("b")
    s.register(a, a.onWire)
    assertEquals(s.dispatchFor(a), Dispatch.Resume(a.onWire))
    assertEquals(s.dispatchFor(b), Dispatch.Fresh(Some(b.onWire)))

  test(
    "ClientClaimed: a claim the backend already holds resumes, never re-claims"
  ):
    // A run interrupted during a session's first turn leaves the transcript
    // written and nothing committed. Claiming that id again is what the CLIs
    // refuse, so the next run must resume against it.
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => true
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("interrupted-id")
    assertEquals(s.dispatchFor(client), Dispatch.Resume(client.onWire))

  test("ServerMinted: a held claim is never inferred — nothing is on the wire"):
    // The client id never reaches a server-minting backend, so a probe that
    // says "yes" to everything must not turn a fresh dispatch into a resume
    // against a fabricated id.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    assertEquals(
      s.dispatchFor(clientSid("never-on-the-wire")),
      Dispatch.Fresh(None)
    )

  test("ServerMinted: Fresh(None) before commit, Resume(server) after"):
    // Codex's contract: the client id is the framework's stable handle; the
    // wire id (server thread id) is what `exec resume` consumes. A fresh
    // dispatch puts NOTHING on the wire — the server mints its own id.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val client = clientSid("client-uuid")
    val server = wireSid("server-thread-xyz")
    assertEquals(s.dispatchFor(client), Dispatch.Fresh(None))
    s.register(client, server)
    assertEquals(s.dispatchFor(client), Dispatch.Resume(server))

  test("ServerMinted: first commit wins — a second commit doesn't overwrite"):
    // The protocol invariant says a resumed session never changes its server
    // id, so a second commit for the same client is either a benign re-commit
    // or a bug. Either way, drop it — don't surprise callers with a
    // silently-changed mapping.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val client = clientSid("client")
    s.register(client, wireSid("server-1"))
    s.register(client, wireSid("server-2"))
    assertEquals(s.dispatchFor(client), Dispatch.Resume(wireSid("server-1")))

  test("ClientClaimed: the stored wire id is the claim, not the reported id"):
    // Under ClientClaimed the client id IS the wire id by protocol; a backend
    // reporting some other id at commit time must not displace the claim.
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => true
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("claimed-id")
    s.commitAfterDrain(
      client,
      WireSessionId[BackendTag.ClaudeCode.type]("reported-other")
    )
    assertEquals(s.persistableWireId(client), Some(client.onWire))

  // ── durability ─────────────────────────────────────────────────────────────

  test("persistableWireId: None before commit, the wire id after (durable)"):
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val client = clientSid("client-uuid")
    val server = wireSid("server-thread-xyz")
    assertEquals(s.persistableWireId(client), None)
    s.register(client, server)
    assertEquals(s.persistableWireId(client), Some(server))

  test(
    "ephemeral: nothing persistable — register still feeds in-run dispatch"
  ):
    val s = SessionSupport.ephemeral[BackendTag.Pi.type](IdScheme.ClientClaimed)
    val id = SessionId.fresh[BackendTag.Pi.type]
    s.register(id, id.onWire)
    assert(s.persistableWireId(id).isEmpty)
    assert(s.dispatchFor(id).isInstanceOf[Dispatch.Resume[?]])

  // ── continuation ───────────────────────────────────────────────────────────

  test("continuation (durable) = recorded mapping AND guarded probe"):
    var probed = List.empty[String]
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      id => { probed = id :: probed; id == "srv-1" }
    )
    val client = SessionId.fresh[BackendTag.Codex.type]
    // No mapping yet — a server-minting backend has no claim to probe either.
    assertEquals(s.continuation(client), Continuation.Rebuild)
    s.register(client, WireSessionId("srv-1"))
    assertEquals(s.continuation(client), Continuation.Recorded)
    assertEquals(probed, List("srv-1"))

  test("continuation (durable): a throwing probe rebuilds"):
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => throw RuntimeException("boom")
    )
    val client = SessionId.fresh[BackendTag.Codex.type]
    s.register(client, WireSessionId("ok-id"))
    assertEquals(s.continuation(client), Continuation.Rebuild)

  test(
    "continuation: a held claim with nothing recorded reads as Claimed"
  ):
    // What the runtime tells apart by: a conversation nothing was recorded for
    // is one an earlier run opened and never committed, so its memory predates
    // this run.
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => true
    )
    val client = SessionId.fresh[BackendTag.ClaudeCode.type]
    assertEquals(s.continuation(client), Continuation.Claimed)
    s.register(client, client.onWire)
    assertEquals(s.continuation(client), Continuation.Recorded)

  test(
    "continuation (ephemeral) reads the in-process claim (Rebuild before, Recorded after)"
  ):
    // An ephemeral backend keeps no durable transcript to probe — but a
    // committed in-process claim IS a live continuation, and the CLI is
    // genuinely told to continue. Answering from a durable probe here would
    // re-seed every turn of a live conversation.
    val s = SessionSupport.ephemeral[BackendTag.Pi.type](IdScheme.ClientClaimed)
    val id = SessionId.fresh[BackendTag.Pi.type]
    assertEquals(s.continuation(id), Continuation.Rebuild, "no claim yet")
    s.register(id, id.onWire)
    assertEquals(
      s.continuation(id),
      Continuation.Recorded,
      "after commit: an in-process continuation"
    )

  // ── the two write-door guards ──────────────────────────────────────────────

  test(
    "register: an invalid wire id records nothing (skip-don't-throw guard)"
  ):
    // An empty (or otherwise unsafe) wire id must be dropped, never recorded, so
    // the next call can't dispatch `resume ""`. It must not throw — completed
    // session output must survive a bookkeeping failure.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val client = SessionId.fresh[BackendTag.Codex.type]
    s.register(client, WireSessionId("")) // must not throw
    assert(
      s.persistableWireId(client).isEmpty,
      "an invalid wire id must leave nothing persistable"
    )

  test(
    "commitAfterDrain: valid id commits, unsafe id throws and records nothing"
  ):
    // The throwing sibling of `register`: the autonomous drain's pre-commit
    // guard, aborting (retryable) rather than logging-and-skipping.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val ok = SessionId.fresh[BackendTag.Codex.type]
    val okWire = WireSessionId[BackendTag.Codex.type]("srv-ok")
    s.commitAfterDrain(ok, okWire)
    assertEquals(s.persistableWireId(ok), Some(okWire))
    val bad = SessionId.fresh[BackendTag.Codex.type]
    val _ = intercept[OrcaFlowException](
      s.commitAfterDrain(bad, WireSessionId(""))
    )
    assert(
      s.persistableWireId(bad).isEmpty,
      "an unsafe id must never be committed"
    )
