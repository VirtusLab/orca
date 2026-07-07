package orca

import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

class EventDispatcherTest extends munit.FunSuite:

  private class RecordingListener extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  test("every listener receives every event in dispatch order"):
    val a = new RecordingListener
    val b = new RecordingListener
    val dispatcher = new EventDispatcher(List(a, b))

    val events = List(
      OrcaEvent.StageStarted("plan"),
      OrcaEvent.Step("hello"),
      OrcaEvent.StageCompleted("plan")
    )
    events.foreach(dispatcher.onEvent)

    assertEquals(a.events, events)
    assertEquals(b.events, events)

  test("listeners are invoked in registration order"):
    val order: AtomicReference[List[String]] = AtomicReference(Nil)
    def tagger(tag: String): OrcaListener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = order.updateAndGet(tag :: _)
    val dispatcher =
      new EventDispatcher(List(tagger("a"), tagger("b"), tagger("c")))
    dispatcher.onEvent(OrcaEvent.StageStarted("s"))
    assertEquals(order.get().reverse, List("a", "b", "c"))

  test("dispatch with no listeners is a no-op"):
    new EventDispatcher(Nil).onEvent(OrcaEvent.StageStarted("x"))

  test("a throwing listener does not stop the others and does not propagate"):
    val received = List.newBuilder[String]
    val bad = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit = throw new RuntimeException("boom")
    val good = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit = received += "good"
    val dispatcher = new EventDispatcher(List(bad, good))
    dispatcher.onEvent(OrcaEvent.Step("x")) // must NOT throw
    assertEquals(received.result(), List("good"))

  test(
    "a throwing listener is quarantined: announced once, skipped afterwards"
  ):
    val badCalls = new AtomicInteger(0)
    val bad = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        badCalls.incrementAndGet(); throw new RuntimeException("boom")
    val received = List.newBuilder[String]
    val good = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit = received += "good"
    val dispatcher = new EventDispatcher(List(bad, good))
    dispatcher.onEvent(OrcaEvent.Step("1"))
    dispatcher.onEvent(OrcaEvent.Step("2"))
    assertEquals(badCalls.get(), 1, "quarantined after the first failure")
    assertEquals(
      received.result(),
      List("good", "good"),
      "healthy listeners unaffected"
    )
