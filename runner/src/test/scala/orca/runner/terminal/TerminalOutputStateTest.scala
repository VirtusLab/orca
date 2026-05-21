package orca.runner.terminal

import java.io.{ByteArrayOutputStream, PrintStream}

class TerminalOutputStateTest extends munit.FunSuite:

  /** ANSI clear-to-EOL is ESC `[2K`; we match on the bytes-after-ESC here
    * because escape characters round-trip through tooling unreliably (Edit-tool
    * strips, terminals consume them, etc.).
    */
  private val ClearSuffix = "[2K"
  private val Esc: Char = ''

  private def withBar(animated: Boolean = true)(
      body: (TerminalOutputState, ByteArrayOutputStream) => Unit
  ): Unit =
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    val bar = new TerminalOutputState(ps, useColor = false, animated = animated)
    body(bar, buf)
    bar.setStatus(None)

  test("appendLog adds a trailing newline when missing"):
    withBar(animated = false): (bar, buf) =>
      bar.log("hello")
      assertEquals(buf.toString, "hello\n")

  test("appendLog preserves a trailing newline already present"):
    withBar(animated = false): (bar, buf) =>
      bar.log("hello\n")
      assertEquals(buf.toString, "hello\n")

  test("appendLog with empty input emits a single newline (used as separator)"):
    withBar(animated = false): (bar, buf) =>
      bar.log("")
      assertEquals(buf.toString, "\n")

  test("non-animated mode emits no ANSI escapes"):
    withBar(animated = false): (bar, buf) =>
      bar.setStatus(Some("running"))
      bar.log("event 1")
      bar.log("event 2")
      val out = buf.toString
      assert(
        !out.contains(Esc),
        s"non-animated mode should never emit ANSI escapes; got: $out"
      )

  test("animated appendLog after startStatus issues clear-to-EOL"):
    withBar(animated = true): (bar, buf) =>
      bar.setStatus(Some("running"))
      bar.log("event 1")
      val out = buf.toString
      val escIndices = out.indices.filter(i => out.charAt(i) == Esc).toList
      assert(
        escIndices.nonEmpty,
        s"animated mode must emit ESC sequences; got prefix: ${out.take(40)}"
      )
      // Every ESC should be followed by `[2K` (the clear-line code).
      val followingIsClear = escIndices.forall: i =>
        i + ClearSuffix.length <= out.length &&
          out.substring(i + 1, i + 1 + ClearSuffix.length) == ClearSuffix
      assert(
        followingIsClear,
        s"each ESC should be followed by '[2K'; out: $out"
      )

  test("startStatus + appendLog redraws the status label after the log line"):
    withBar(animated = true): (bar, buf) =>
      bar.setStatus(Some("running"))
      bar.log("event 1")
      val out = buf.toString
      // The label should appear twice — once on initial draw, once on
      // re-draw after the log line landed above it.
      val occurrences = out.split("running", -1).length - 1
      assert(
        occurrences >= 2,
        s"expected the status label to be redrawn at least twice; out: $out"
      )

  test("long status labels are truncated to one line"):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    val bar = new TerminalOutputState(ps, useColor = false, animated = true)
    val veryLong = "x" * 500
    bar.setStatus(Some(veryLong))
    bar.setStatus(None)
    val out = buf.toString
    // Find the spinner-prefixed payload between the first ESC[2K and
    // the next ESC. The inner segment must be shorter than the input
    // and contain the ellipsis.
    val firstEsc = out.indexOf(Esc)
    assert(firstEsc >= 0, "expected at least one ANSI escape")
    val afterEsc = out.indexOf("[2K", firstEsc) + "[2K".length
    val nextEsc = out.indexOf(Esc, afterEsc) match
      case -1 => out.length
      case i  => i
    val rendered = out.substring(afterEsc, nextEsc)
    assert(
      rendered.length < veryLong.length,
      s"status line should be truncated; got ${rendered.length} chars"
    )
    assert(rendered.contains("…"), s"expected ellipsis; got: '$rendered'")

  test("stopStatus erases the status row in animated mode"):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    val bar = new TerminalOutputState(ps, useColor = false, animated = true)
    bar.setStatus(Some("running"))
    bar.setStatus(None)
    val bytes = buf.toByteArray.toList
    val tail = bytes.takeRight(4)
    val expected = List(0x1b, '['.toByte, '2'.toByte, 'K'.toByte).map(_.toByte)
    assertEquals(
      tail,
      expected,
      s"output should end with ESC+[2K; got tail bytes: $tail"
    )
