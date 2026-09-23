package orca.backend

import ox.supervised

class TurnResourcesTest extends munit.FunSuite:

  test("a failing release does not fail the scope"):
    val result = supervised:
      val _ = TurnResources.use("resource")(_ => throw RuntimeException("boom"))
      "turn result"
    assertEquals(result, "turn result")
