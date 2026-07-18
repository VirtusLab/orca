package orca.shell.flows

class FlowViewerTest extends munit.FunSuite:

  private val source =
    """// A tiny flow.
      |//> using scala 3.8.4
      |
      |def greet(name: String): Unit =
      |  println(s"Hello, $name")
      |""".stripMargin

  test("non-tty render is byte-identical to the input"):
    assertEquals(FlowViewer.render(source, tty = false), source)

  test("tty render contains ANSI escapes and strips back to the input"):
    val rendered = FlowViewer.render(source, tty = true)
    assert(rendered.contains("["), "expected an ANSI escape sequence in tty output")
    assertEquals(fansi.Str(rendered, fansi.ErrorMode.Sanitize).plainText, source)
