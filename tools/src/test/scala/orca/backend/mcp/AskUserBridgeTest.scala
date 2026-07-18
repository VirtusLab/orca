package orca.backend.mcp

import ox.{forkUser, supervised}
import ox.channels.{BufferCapacity, ChannelClosedException}

class AskUserBridgeTest extends munit.FunSuite:

  test("ask blocks until the host calls respond"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      val askResult = forkUser:
        bridge.ask("hello?")

      val pending = bridge.nextQuestion()
      assertEquals(pending.question, "hello?")
      pending.respond("world")

      assertEquals(askResult.join(), "world")

  test("concurrent asks don't cross wires"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      // Arrival order at the queue is non-deterministic. The host routes each
      // reply by matching question content; each fork must receive its own
      // answer, never the sibling's.
      val first = forkUser(bridge.ask("Q1"))
      val second = forkUser(bridge.ask("Q2"))

      def serveOne(): Unit =
        val q = bridge.nextQuestion()
        q.respond(if q.question == "Q1" then "A1" else "A2")

      serveOne()
      serveOne()

      assertEquals(first.join(), "A1")
      assertEquals(second.join(), "A2")

  test("close unblocks an in-flight ask with ChannelClosedException"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      val askFork = forkUser:
        try
          val _ = bridge.ask("blocked?")
          "completed-unexpectedly"
        catch case _: ChannelClosedException => "closed"

      // nextQuestion ensures the ask reached the rendezvous — its reply channel
      // is registered as in-flight — before we close.
      val pending = bridge.nextQuestion()
      assertEquals(pending.question, "blocked?")

      bridge.close()
      assertEquals(askFork.join(), "closed")

  test("close is idempotent"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge
      bridge.close()
      bridge.close() // must not throw

  test("respond after ask exits early is a no-op, not a deadlock"):
    // If the handler thread unwinds before respond is called (e.g. the HTTP
    // client aborts the request), the orphaned reply channel must be `done`'d
    // so the renderer's later respond doesn't block forever on a receiver-less send.
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      val askFork = forkUser:
        try
          val _ = bridge.ask("anyone there?")
          "completed-unexpectedly"
        catch case _: ChannelClosedException => "closed"

      val pending = bridge.nextQuestion()
      assertEquals(pending.question, "anyone there?")

      // Simulate the handler exiting early by closing the bridge, which `done`s
      // every in-flight reply channel.
      bridge.close()
      assertEquals(askFork.join(), "closed")

      // The renderer, unaware the handler is gone, eventually responds; the
      // closed-channel send must be a no-op rather than hang forever.
      pending.respond("the answer that arrived too late")
