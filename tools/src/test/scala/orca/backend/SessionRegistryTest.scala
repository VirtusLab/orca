package orca.backend

import orca.agents.{BackendTag, SessionId, WireSessionId, onWire}

class SessionRegistryTest extends munit.FunSuite:

  private def sid(s: String): SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](s)

  private def clientSid(s: String): SessionId[BackendTag.Codex.type] =
    SessionId[BackendTag.Codex.type](s)

  private def wireSid(s: String): WireSessionId[BackendTag.Codex.type] =
    WireSessionId[BackendTag.Codex.type](s)

  test(
    "ClaimedOnce: dispatchFor flips Fresh → Resume after commitSuccess"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]
    val client = sid("client-A")
    assertEquals(reg.dispatchFor(client), Dispatch.Fresh(Some(client.onWire)))
    reg.commitSuccess(client, client.onWire)
    assertEquals(reg.dispatchFor(client), Dispatch.Resume(client.onWire))

  test(
    "ClaimedOnce: distinct client ids are tracked independently"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]
    val a = sid("a")
    val b = sid("b")
    reg.commitSuccess(a, a.onWire)
    assertEquals(reg.dispatchFor(a), Dispatch.Resume(a.onWire))
    assertEquals(reg.dispatchFor(b), Dispatch.Fresh(Some(b.onWire)))

  test(
    "ClientToServer: dispatchFor returns Resume with the recorded server id"
  ):
    // Codex's contract: the client id is the framework's stable handle;
    // the wire id (server thread id) is what `exec resume` consumes.
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = clientSid("client-uuid")
    val server = wireSid("server-thread-xyz")
    // ClientToServer never puts a client id on the wire: fresh dispatch is
    // `Fresh(None)` (the server mints its own id at first use).
    assertEquals(reg.dispatchFor(client), Dispatch.Fresh(None))
    reg.commitSuccess(client, server)
    assertEquals(reg.dispatchFor(client), Dispatch.Resume(server))

  test(
    "ClientToServer: resumeWireId is None before commit, Some(server) after"
  ):
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = clientSid("client-uuid")
    val server = wireSid("server-thread-xyz")
    assertEquals(reg.resumeWireId(client), None)
    reg.commitSuccess(client, server)
    assertEquals(reg.resumeWireId(client), Some(server))

  test(
    "ClaimedOnce: resumeWireId is None before commit, Some(client) after"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]
    val client = sid("client-A")
    val wire: WireSessionId[BackendTag.ClaudeCode.type] = client.onWire
    assertEquals(reg.resumeWireId(client), None)
    reg.commitSuccess(client, wire)
    assertEquals(reg.resumeWireId(client), Some(wire))

  test(
    "ClientToServer: putIfAbsent semantics — second commit doesn't overwrite"
  ):
    // The codex protocol invariant says a resumed session never changes
    // its server id, so a second commit for the same client is either a
    // benign re-commit or a bug. Either way, drop it — don't surprise
    // callers with a silently-changed mapping.
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = clientSid("client")
    val first = wireSid("server-1")
    val second = wireSid("server-2")
    reg.commitSuccess(client, first)
    reg.commitSuccess(client, second)
    assertEquals(reg.dispatchFor(client), Dispatch.Resume(first))
