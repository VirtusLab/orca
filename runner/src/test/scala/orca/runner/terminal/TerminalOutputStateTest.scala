package orca.runner.terminal

import java.io.{ByteArrayOutputStream, PrintStream}

class TerminalOutputStateTest extends munit.FunSuite:

  /** ANSI clear-to-EOL is ESC `[2K`; we match on the bytes-after-ESC here
    * because escape characters round-trip through tooling unreliably (Edit-tool
    * strips, terminals consume them, etc.).
    */
  private val ClearSuffix = "[2K"
  private val Esc: Char = '\u001b'

  private def withBar(animated: Boolean)(
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

  test("suspend buffers subsequent log calls; resume drains them in order"):
    withBar(animated = false): (bar, buf) =>
      bar.log("before-suspend")
      bar.suspend()
      val sizeAtSuspend = buf.size()
      bar.log("during-1")
      bar.log("during-2")
      assertEquals(
        buf.size(),
        sizeAtSuspend,
        "suspended log calls must not write to `out`"
      )
      bar.resume()
      val out = buf.toString
      assert(
        out.contains("before-suspend"),
        s"pre-suspend log lost; out: $out"
      )
      val drained = out.substring(out.indexOf("before-suspend"))
      val d1 = drained.indexOf("during-1")
      val d2 = drained.indexOf("during-2")
      assert(d1 >= 0 && d2 >= 0, s"buffered logs missing on resume: $drained")
      assert(d1 < d2, "buffered logs drained out of order")

  test("suspend short-circuits tick: no spinner redraw between suspend/resume"):
    withBar(animated = true): (bar, buf) =>
      bar.setStatus(Some("running"))
      bar.suspend()
      val sizeAtSuspend = buf.size()
      bar.tick()
      bar.tick()
      bar.tick()
      assertEquals(
        buf.size(),
        sizeAtSuspend,
        "tick must not draw while suspended"
      )
      bar.resume()
      // Resume redraws the status row once.
      val drawnAfterResume = buf.toString.substring(sizeAtSuspend)
      assert(
        drawnAfterResume.contains("running"),
        s"resume should redraw the status; drawn: '$drawnAfterResume'"
      )

  test("close drains any pending suspended buffer"):
    withBar(animated = false): (bar, buf) =>
      bar.suspend()
      bar.log("orphan-1")
      bar.log("orphan-2")
      bar.close()
      val out = buf.toString
      assert(out.contains("orphan-1"), s"buffered log lost on close: $out")
      assert(out.contains("orphan-2"), s"buffered log lost on close: $out")

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
