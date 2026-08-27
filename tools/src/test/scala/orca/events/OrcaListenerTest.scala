package orca.events

import java.util.concurrent.atomic.AtomicReference

class OrcaListenerTest extends munit.FunSuite:

  test("attributedTo stamps an Error and leaves a non-display event alone"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener = OrcaListener.attributedTo(
      e => seen.updateAndGet(_ :+ e): Unit,
      "readability"
    )
    listener.onEvent(OrcaEvent.Error("turn failed"))
    listener.onEvent(OrcaEvent.Step("Switched to branch 'main'"))
    assertEquals(
      seen.get(),
      List(
        OrcaEvent.Error("turn failed", Some("readability")),
        OrcaEvent.Step("Switched to branch 'main'")
      )
    )
