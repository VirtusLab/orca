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
      _ => true
    )
    val client = SessionId[BackendTag.ClaudeCode.type]("client-A")
    assertEquals(s.dispatchFor(client), Dispatch.Fresh(Some(client.onWire)))
    s.register(client, client.onWire)
    assertEquals(s.dispatchFor(client), Dispatch.Resume(client.onWire))

  test("ClientClaimed: distinct client ids are tracked independently"):
    val s = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ClientClaimed,
      _ => true
    )
    val a = SessionId[BackendTag.ClaudeCode.type]("a")
    val b = SessionId[BackendTag.ClaudeCode.type]("b")
    s.register(a, a.onWire)
    assertEquals(s.dispatchFor(a), Dispatch.Resume(a.onWire))
    assertEquals(s.dispatchFor(b), Dispatch.Fresh(Some(b.onWire)))

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

  // ── willContinue ───────────────────────────────────────────────────────────

  test("willContinue (durable) = recorded mapping AND guarded probe"):
    var probed = List.empty[String]
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      id => { probed = id :: probed; id == "srv-1" }
    )
    val client = SessionId.fresh[BackendTag.Codex.type]
    assert(!s.willContinue(client)) // no mapping yet — probe not called
    s.register(client, WireSessionId("srv-1"))
    assert(s.willContinue(client) && probed == List("srv-1"))

  test("willContinue (durable): a throwing probe is 'won't continue'"):
    // No isSafe re-check on the resolved wire id here: every id that reaches
    // the bookkeeping does so through `register`/`commitAfterDrain`, both of
    // which already validate it — the unsafe-id defense is pinned at those
    // two tests below. This test covers the other absence case: a probe that
    // throws.
    val s = SessionSupport.durable[BackendTag.Codex.type](
      IdScheme.ServerMinted,
      _ => throw RuntimeException("boom")
    )
    val client = SessionId.fresh[BackendTag.Codex.type]
    s.register(client, WireSessionId("ok-id"))
    assert(!s.willContinue(client))

  test(
    "willContinue (ephemeral) reads the in-process claim (false before, true after)"
  ):
    // An ephemeral backend (pi) keeps no durable transcript to probe — but a
    // committed in-process claim IS a live continuation, and the CLI is
    // genuinely told to continue. Answering from a durable probe here would
    // re-seed every turn of a live conversation.
    val s = SessionSupport.ephemeral[BackendTag.Pi.type](IdScheme.ClientClaimed)
    val id = SessionId.fresh[BackendTag.Pi.type]
    assert(!s.willContinue(id), "first call: no claim yet")
    s.register(id, id.onWire)
    assert(s.willContinue(id), "after commit: an in-process continuation")

  // ── the two write-door guards ──────────────────────────────────────────────

  test(
    "register: an invalid wire id records nothing (skip-don't-throw guard)"
  ):
    // The central guard covering the interactive + rehydration paths: an empty
    // (or otherwise unsafe) wire id must be logged-and-dropped, never recorded,
    // so the NEXT call can't dispatch `resume ""`. It must NOT throw — the
    // completed session output / setup must survive a bookkeeping failure.
    // The SessionSupport-level test suffices; `Agent.registerResumeWireId`
    // funnels straight into this same `register`, so no separate harness is
    // needed to exercise the rehydration path's guard.
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
