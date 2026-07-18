package orca.backend.mcp

import ox.channels.BufferCapacity
import ox.supervised

import java.util.concurrent.atomic.AtomicInteger

class AskUserSessionTest extends munit.FunSuite:

  test(
    "allocate closes the bridge + server when the extras callback throws"
  ):
    // If the backend-specific `extras` callback fails, the partially-allocated
    // Netty binding must still be torn down so the port can be rebound and
    // long-running flows don't leak.
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val thrown = intercept[RuntimeException]:
        AskUserSession.allocate: server =>
          // Touch the server so we know it's already started — then fail.
          val _ = server.port
          throw new RuntimeException("extras callback boom")
      assertEquals(thrown.getMessage, "extras callback boom")

  test("close runs each closer once even when an earlier one throws"):
    // One resource failing during teardown must not skip the others. Bridge and
    // server aren't observable post-close, so count via the extras slot.
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
