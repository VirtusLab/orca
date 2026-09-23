package orca.progress

import munit.FunSuite

class FlowSourceTest extends FunSuite:

  test("a File source survives the system-property encoding"):
    val source = FlowSource.File("/home/u/my flows/x=y,z.sc")
    assertEquals(
      FlowSource.fromProperty(FlowSource.toProperty(source)),
      Right(source)
    )

  test("a property value that isn't a FlowSource is refused"):
    assert(FlowSource.fromProperty("implement.sc").isLeft)
