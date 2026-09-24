package orca.backend

import orca.AgentTurnFailed
import orca.events.{TurnDebit, Usage}
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
    assertEquals(
      s.dispatchFor(client),
      Dispatch.Resume(client.onWire, ResumeOrigin.ThisAttempt)
    )

  test("ClientClaimed: distinct client ids are tracked independently"):
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => false
    )
    val a = SessionId[BackendTag.ClaudeCode.type]("a")
    val b = SessionId[BackendTag.ClaudeCode.type]("b")
    s.register(a, a.onWire)
    assertEquals(
      s.dispatchFor(a),
      Dispatch.Resume(a.onWire, ResumeOrigin.ThisAttempt)
    )
    assertEquals(s.dispatchFor(b), Dispatch.Fresh(Some(b.onWire)))

  test(
    "ClientClaimed: a claim the backend already holds resumes, never re-claims"
  ):
    // An attempt interrupted during a session's first turn leaves the transcript
    // written and nothing committed. Claiming that id again is what the CLIs
    // refuse, so the next attempt must resume against it.
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => true
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("interrupted-id")
    assertEquals(
      s.dispatchFor(client),
      Dispatch.Resume(client.onWire, ResumeOrigin.EarlierAttempt)
    )

  test("ClientClaimed: a held claim is probed once, then settled"):
    var probes = 0
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => { probes += 1; true }
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("interrupted-id")
    val expected = Dispatch.Resume(client.onWire, ResumeOrigin.EarlierAttempt)
    assertEquals(s.dispatchFor(client), expected)
    s.register(client, client.onWire)
    assertEquals(s.dispatchFor(client), expected)
    assertEquals(probes, 1)

  test("ClientClaimed: an unsafe client id is never probed"):
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => fail("the probe must not see an unsafe id")
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("../../etc/passwd")
    assertEquals(s.dispatchFor(client), Dispatch.Fresh(Some(client.onWire)))

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
    assertEquals(
      s.dispatchFor(client),
      Dispatch.Resume(server, ResumeOrigin.ThisAttempt)
    )

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
    assertEquals(
      s.dispatchFor(client),
      Dispatch.Resume(wireSid("server-1"), ResumeOrigin.ThisAttempt)
    )

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
      AgentResult(
        WireSessionId[BackendTag.ClaudeCode.type]("reported-other"),
        "",
        Usage.empty,
        model = None
      )
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

  // ── rehydrated wire ids ────────────────────────────────────────────────────

  test("rehydrated id: probed once, then resumed as an earlier attempt's"):
    var probed = List.empty[String]
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      id => { probed = id :: probed; true }
    )
    val client = clientSid("client")
    s.rehydrate(client, wireSid("srv-1"))
    val expected =
      Dispatch.Resume(wireSid("srv-1"), ResumeOrigin.EarlierAttempt)
    assertEquals(s.dispatchFor(client), expected)
    assertEquals(s.dispatchFor(client), expected)
    assertEquals(probed, List("srv-1"))

  test("rehydrated id the backend no longer holds: Fresh"):
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => false
    )
    val client = clientSid("client")
    s.rehydrate(client, wireSid("lost"))
    assertEquals(s.dispatchFor(client), Dispatch.Fresh(None))

  test(
    "rehydrated id the backend no longer holds: the next commit replaces it"
  ):
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => false
    )
    val client = clientSid("client")
    s.rehydrate(client, wireSid("lost"))
    val _ = s.dispatchFor(client)
    s.commitAfterDrain(
      client,
      AgentResult(wireSid("new"), "", Usage.empty, model = None)
    )
    assertEquals(s.persistableWireId(client), Some(wireSid("new")))

  test("rehydrated id with a throwing probe: Fresh"):
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => throw RuntimeException("boom")
    )
    val client = clientSid("client")
    s.rehydrate(client, wireSid("ok-id"))
    assertEquals(s.dispatchFor(client), Dispatch.Fresh(None))

  test("rehydrate: an invalid wire id records nothing"):
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val client = clientSid("client")
    s.rehydrate(client, wireSid("../etc"))
    assertEquals(s.persistableWireId(client), None)

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

  test("commitAfterDrain: an unsafe id fails the turn and records nothing"):
    // The throwing sibling of `register`: the turn has run, so it fails (not
    // retried, usage kept) rather than logging-and-skipping.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => true
    )
    val bad = SessionId.fresh[BackendTag.Codex.type]
    val usage = Usage.empty.copy(outputTokens = 7L)
    val failed = intercept[AgentTurnFailed](
      s.commitAfterDrain(
        bad,
        AgentResult(WireSessionId(""), "", usage, model = None)
      )
    )
    assertEquals(failed.debit, TurnDebit.Observed(usage, None))
    assert(
      s.persistableWireId(bad).isEmpty,
      "an unsafe id must never be committed"
    )
