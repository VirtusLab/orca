package orca.backend

import ox.supervised

import java.util.concurrent.atomic.AtomicBoolean

class TurnResourcesTest extends munit.FunSuite:

  test("a failing release runs at scope end without failing the scope"):
    val released = new AtomicBoolean(false)
    val result = supervised:
      val _ = TurnResources.use("resource"): _ =>
        released.set(true)
        throw RuntimeException("boom")
      "turn result"
    assertEquals(result, "turn result")
    assert(released.get())
