package orca.backend

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

  test("Durable: unsafe wire id and throwing probe are both 'absent'"):
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = SessionId.fresh[BackendTag.Codex.type]
    reg.commitSuccess(client, WireSessionId("../etc"))
    assert(!SessionSupport.Durable(reg, _ => true).exists(client))
    val client2 = SessionId.fresh[BackendTag.Codex.type]
    reg.commitSuccess(client2, WireSessionId("ok-id"))
    assert(
      !SessionSupport
        .Durable(reg, _ => throw RuntimeException("boom"))
        .exists(client2)
    )
