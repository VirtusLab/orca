package orca

import munit.FunSuite
import orca.progress.FlowSource

class FlowSourcePropertyTest extends FunSuite:

  test("a File source survives the --java-prop assignment"):
    val source = FlowSource.File("/home/u/my flows/x=y,z.sc")
    val value = FlowSourceProperty
      .assignment(source)
      .stripPrefix(s"${FlowSourceProperty.Name}=")
    assertEquals(FlowSourceProperty.decode(value), Right(source))

  test("a property value that isn't a FlowSource is refused"):
    assert(FlowSourceProperty.decode("implement.sc").isLeft)
