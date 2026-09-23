package orca.progress

import munit.FunSuite

class FlowSourceTest extends FunSuite:

  test("a File source's fileName is the path's last segment"):
    assertEquals(FlowSource.File("/a/b/x.sc").fileName, "x.sc")
