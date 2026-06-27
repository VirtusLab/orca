package orca.backend

import orca.agents.{BackendTag, SessionId}

class SessionRegistryTest extends munit.FunSuite:

  private def sid(s: String): SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](s)

  private def serverSid(s: String): SessionId[BackendTag.Codex.type] =
    SessionId[BackendTag.Codex.type](s)

  test(
    "ClaimedOnce: dispatchFor flips Fresh → Resume after commitSuccess"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]
    val client = sid("client-A")
    assertEquals(reg.dispatchFor(client), Dispatch.Fresh(client))
    reg.commitSuccess(client, client)
    assertEquals(reg.dispatchFor(client), Dispatch.Resume(client))

  test(
    "ClaimedOnce: distinct client ids are tracked independently"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]
    val a = sid("a")
    val b = sid("b")
    reg.commitSuccess(a, a)
    assertEquals(reg.dispatchFor(a), Dispatch.Resume(a))
    assertEquals(reg.dispatchFor(b), Dispatch.Fresh(b))

  test(
    "ClientToServer: dispatchFor returns Resume with the recorded server id"
  ):
    // Codex's contract: the client id is the framework's stable handle;
    // the wire id (server thread id) is what `exec resume` consumes.
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = serverSid("client-uuid")
    val server = serverSid("server-thread-xyz")
    assertEquals(reg.dispatchFor(client), Dispatch.Fresh(client))
    reg.commitSuccess(client, server)
    assertEquals(reg.dispatchFor(client), Dispatch.Resume(server))

  test(
    "ClientToServer: resumeWireId is None before commit, Some(server) after"
  ):
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = serverSid("client-uuid")
    val server = serverSid("server-thread-xyz")
    assertEquals(reg.resumeWireId(client), None)
    reg.commitSuccess(client, server)
    assertEquals(reg.resumeWireId(client), Some(server))

  test(
    "ClaimedOnce: resumeWireId is None before commit, Some(client) after"
  ):
    val reg = new SessionRegistry.ClaimedOnce[BackendTag.ClaudeCode.type]
    val client = sid("client-A")
    assertEquals(reg.resumeWireId(client), None)
    reg.commitSuccess(client, client)
    assertEquals(reg.resumeWireId(client), Some(client))

  test(
    "ClientToServer: putIfAbsent semantics — second commit doesn't overwrite"
  ):
    // The codex protocol invariant says a resumed session never changes
    // its server id, so a second commit for the same client is either a
    // benign re-commit or a bug. Either way, drop it — don't surprise
    // callers with a silently-changed mapping.
    val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
    val client = serverSid("client")
    val first = serverSid("server-1")
    val second = serverSid("server-2")
    reg.commitSuccess(client, first)
    reg.commitSuccess(client, second)
    assertEquals(reg.dispatchFor(client), Dispatch.Resume(first))
