package orca.backend.mcp

import ox.{discard, forkCancellable, forkUser, supervised}
import ox.channels.BufferCapacity

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

  test("respond after ask exits early is a no-op, not a deadlock"):
    // If the handler unwinds before respond is called (the turn scope
    // interrupting it), the orphaned reply channel must be `done`'d so the
    // renderer's later respond doesn't block forever on a receiver-less send.
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge

      val askFork = forkCancellable(bridge.ask("anyone there?"))

      val pending = bridge.nextQuestion()
      assertEquals(pending.question, "anyone there?")

      askFork.cancel().discard

      // The renderer, unaware the handler is gone, eventually responds; the
      // closed-channel send must be a no-op rather than hang forever.
      pending.respond("the answer that arrived too late")
