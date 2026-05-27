package orca.runner.terminal

import ox.channels.BufferCapacity
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration.DurationInt

/** Regression coverage for the spinner-during-drive bug: the animator fork must
  * keep advancing ticks even while another thread is keeping the actor busy
  * with `log` calls.
  */
class TerminalOutputActorTest extends munit.FunSuite:

  private val Esc: Char = '\u001b'

  test("animator advances ticks autonomously once a status label is set"):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    supervised:
      given BufferCapacity = BufferCapacity(64)
      val output = TerminalOutput.start(
        ps,
        useColor = false,
        animated = true,
        framePeriodMs = 20L
      )
      output.setStatus(Some("running"))
      // Give the animator several frame periods to land; it runs on its
      // own fork so this main-thread sleep doesn't block its ticks.
      Thread.sleep(120)
      output.close()
      val ticks = buf.size()
      assert(
        ticks > 0,
        s"expected the animator to have written at least one frame, got $ticks bytes"
      )

  test(
    "spinner advances during a separate thread's stream of log tells"
  ):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    supervised:
      given BufferCapacity = BufferCapacity(256)
      val output = TerminalOutput.start(
        ps,
        useColor = false,
        animated = true,
        framePeriodMs = 20L
      )
      output.setStatus(Some("running"))
      // Hammer log tells from this thread for ~200ms; animator should
      // still interleave ticks since both go through the same mailbox
      // and each handler is short.
      val deadline = System.nanoTime() + 200.millis.toNanos
      while System.nanoTime() < deadline do output.log("event")
      // Give the animator one last frame period to land.
      Thread.sleep(40)
      output.close()
      val out = buf.toString
      // Multiple ESC[2K clear-line sequences must appear: one per log
      // write (clearing the status row), plus tick redraws between
      // writes. We require at least 10 to ensure ticks/logs interleaved
      // many times rather than one drowning the other.
      val clears = java.util.regex.Pattern.quote(s"$Esc[2K")
      val matchCount = clears.r.findAllMatchIn(out).length
      assert(
        matchCount >= 10,
        s"expected many ESC[2K clears (ticks + logs interleaving); got $matchCount"
      )
