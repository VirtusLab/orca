package orca

import orca.events.{
  CostTracker,
  EventDispatcher,
  OrcaEvent,
  OrcaListener,
  Usage
}

import java.util.concurrent.atomic.AtomicReference

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
      OrcaEvent.StageCompleted("plan", "ok")
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

  test("a throwing listener propagates and stops later listeners from running"):
    val after = new RecordingListener
    val throwing: OrcaListener = _ => throw new RuntimeException("boom")
    val dispatcher = new EventDispatcher(List(throwing, after))
    val _ = intercept[RuntimeException]:
      dispatcher.onEvent(OrcaEvent.StageStarted("x"))
    assertEquals(after.events, Nil)
