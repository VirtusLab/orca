package orca.tools.claude.mcp

import ox.{forkUser, supervised}
import ox.channels.{BufferCapacity, ChannelClosedException}

class AskUserBridgeTest extends munit.FunSuite:

  test("ask blocks until take's respond is called"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      val askResult = forkUser:
        bridge.ask("hello?")

      val (question, respond) = bridge.take()
      assertEquals(question, "hello?")
      respond("world")

      assertEquals(askResult.join(), "world")

  test("concurrent asks don't cross wires"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      // Two parallel asks; arrival order at the queue is non-deterministic
      // (depends on fork scheduling). The host loop routes each reply by
      // matching on the question content; each fork must receive *its* own
      // matching answer, never the sibling's.
      val first = forkUser(bridge.ask("Q1"))
      val second = forkUser(bridge.ask("Q2"))

      def serveOne(): Unit =
        val (q, respond) = bridge.take()
        respond(if q == "Q1" then "A1" else "A2")

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

      // Take ensures the ask reached the rendezvous (so its reply channel
      // is registered as in-flight) before we close.
      val (q, _) = bridge.take()
      assertEquals(q, "blocked?")

      bridge.close()
      assertEquals(askFork.join(), "closed")

  test("close is idempotent"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge
      bridge.close()
      bridge.close() // must not throw
