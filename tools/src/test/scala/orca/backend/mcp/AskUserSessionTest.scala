package orca.backend.mcp

import ox.channels.BufferCapacity
import ox.supervised

import java.util.concurrent.atomic.AtomicInteger

class AskUserSessionTest extends munit.FunSuite:

  test(
    "allocate closes the bridge + server when the extras callback throws"
  ):
    // Pins the defensive cleanup in `allocate`'s catch block: if the
    // backend-specific `extras` callback fails (e.g. workDir write fails),
    // the partially-allocated Netty binding must still be torn down so
    // the test process can rebind the same port and so long-running
    // flows don't leak.
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val thrown = intercept[RuntimeException]:
        AskUserSession.allocate: server =>
          // Touch the server so we know it's already started — then fail.
          val _ = server.port
          throw new RuntimeException("extras callback boom")
      assertEquals(thrown.getMessage, "extras callback boom")

  test("close runs each closer once even when an earlier one throws"):
    // Pins the per-step `swallow`: one resource failing during teardown
    // must not skip the others. We can't see bridge/server post-close,
    // so use the extras slot to count.
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val calls = new AtomicInteger(0)
      val throwingFirst: AutoCloseable = () =>
        val _ = calls.incrementAndGet()
        throw new RuntimeException("first close boom")
      val secondClose: AutoCloseable = () =>
        val _ = calls.incrementAndGet()
      val resources = AskUserSession.allocate: _ =>
        List(throwingFirst, secondClose)
      resources.close()
      assertEquals(calls.get(), 2, "every extras closer must run")
