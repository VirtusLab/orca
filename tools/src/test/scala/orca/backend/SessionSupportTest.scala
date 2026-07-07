package orca.backend

import orca.OrcaFlowException
import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}

class SessionSupportTest extends munit.FunSuite:

  test(
    "Ephemeral: never durable — exists false, nothing persistable, register still feeds in-run dispatch"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.Pi.type]
    val s = SessionSupport.Ephemeral(reg)
    val id = SessionId.fresh[BackendTag.Pi.type]
    s.register(id, id.onWire)
    assert(!s.exists(id) && s.persistableWireId(id).isEmpty)
    assert(reg.dispatchFor(id).isInstanceOf[Dispatch.Resume[?]])

  test("Durable: exists = registry mapping AND guarded probe"):
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    var probed = List.empty[String]
    val s = SessionSupport.Durable(
      reg,
      id => { probed = id :: probed; id == "srv-1" }
    )
    val client = SessionId.fresh[BackendTag.Codex.type]
    assert(!s.exists(client)) // no mapping yet — probe not called
    s.register(client, WireSessionId("srv-1"))
    assert(s.exists(client) && probed == List("srv-1"))

  test("Durable: a throwing probe is 'absent'"):
    // `exists`'s own isSafe re-check on the resolved wire id was removed as
    // provably dead (6B.3): every id that reaches the registry does so
    // through `register`/`commitAfterDrain` below, both of which already
    // validate it — a second check here would guard against an id that can
    // no longer exist. The unsafe-id defense is pinned at those two tests
    // ("register: an invalid wire id records nothing", "commitAfterDrain:
    // valid id commits, unsafe id throws"), not here; this test keeps only
    // `exists`'s other absence case: a probe that throws.
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = SessionId.fresh[BackendTag.Codex.type]
    reg.commitSuccess(client, WireSessionId("ok-id"))
    assert(
      !SessionSupport
        .Durable(reg, _ => throw RuntimeException("boom"))
        .exists(client)
    )

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
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = SessionId.fresh[BackendTag.Codex.type]
    val s = SessionSupport.Durable(reg, _ => true)
    s.register(client, WireSessionId("")) // must not throw
    assert(
      s.persistableWireId(client).isEmpty,
      "an invalid wire id must leave nothing persistable"
    )

  test(
    "willContinue: Ephemeral reads the in-process claim (false before, true after)"
  ):
    // The 6.6 fix: `exists` is hardcoded false for Ephemeral, so the durable
    // runtime must ask `willContinue` instead — which reflects the registry's
    // in-process claim so a live pi conversation is NOT re-seeded next turn.
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.Pi.type]
    val s = SessionSupport.Ephemeral(reg)
    val id = SessionId.fresh[BackendTag.Pi.type]
    assert(!s.willContinue(id), "first call: no claim yet")
    s.register(id, id.onWire)
    assert(s.willContinue(id), "after commit: an in-process continuation")
    assert(!s.exists(id), "exists stays false — nothing durable to probe")

  test("willContinue: Durable delegates to the probe (same as exists)"):
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = SessionId.fresh[BackendTag.Codex.type]
    val s = SessionSupport.Durable(reg, id => id == "srv-1")
    assert(!s.willContinue(client)) // no mapping — probe not reached
    s.register(client, WireSessionId("srv-1"))
    assert(s.willContinue(client)) // mapping + probe true

  test(
    "commitAfterDrain: valid id commits, unsafe id throws and records nothing"
  ):
    // The throwing sibling of `register`: the autonomous drain's pre-commit
    // guard, aborting (retryable) rather than logging-and-skipping.
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val s = SessionSupport.Durable(reg, _ => true)
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
