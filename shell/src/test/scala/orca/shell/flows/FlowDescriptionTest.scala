package orca.shell.flows

class FlowDescriptionTest extends munit.FunSuite:

  test("line-1 comment before directives"):
    assertEquals(FlowDescription.extract(List("// Do the thing.", "//> using scala 3.8.4", "val x = 1")), Some("Do the thing."))

  test("comment after directives still found"):
    assertEquals(FlowDescription.extract(List("//> using scala 3.8.4", "", "// Later.", "code")), Some("Later."))

  test("directive is not a description"):
    assertEquals(FlowDescription.extract(List("//> using scala 3.8.4", "val x = 1")), None)

  test("block comment terminates the scan"):
    assertEquals(FlowDescription.extract(List("/** doc */", "// not reached")), None)

  test("empty input has no description"):
    assertEquals(FlowDescription.extract(List.empty[String]), None)

  test("blank-only lines before end of input have no description"):
    assertEquals(FlowDescription.extract(List("", "  ", "")), None)

  test("a comment line with only whitespace after the marker is not a description"):
    assertEquals(FlowDescription.extract(List("//   ", "// Real one.")), Some("Real one."))
