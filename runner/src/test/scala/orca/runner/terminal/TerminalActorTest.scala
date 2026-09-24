package orca.runner.terminal

import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import ox.channels.BufferCapacity
import ox.{fork, supervised}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** Regression coverage for the spinner-during-drive bug: the animator fork must
  * keep advancing ticks even while another thread is keeping the actor busy
  * with `log` calls.
  */
class TerminalActorTest extends munit.FunSuite:

  private val Esc: Char = '\u001b'

  test("animator advances ticks autonomously once a status label is set"):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    supervised:
      given BufferCapacity = BufferCapacity(64)
      val output = TerminalActor.start(
        ps,
        useColor = false,
        animated = true,
        workDir = None,
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

  test("spinner advances during a separate thread's stream of log calls"):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    supervised:
      given BufferCapacity = BufferCapacity(256)
      val output = TerminalActor.start(
        ps,
        useColor = false,
        animated = true,
        workDir = None,
        framePeriodMs = 20L
      )
      output.setStatus(Some("running"))
      // Hammer log calls from this thread for ~200ms; animator should
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

  test(
    "prompt() serializes two concurrent forks: the second's readUser only " +
      "starts after the first's bracket (through resume) has closed, and " +
      "log/setStatus issued while the first prompt is open stay buffered"
  ):
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    supervised:
      given BufferCapacity = BufferCapacity(64)
      val output = TerminalActor.start(
        ps,
        useColor = false,
        animated = true,
        workDir = None,
        framePeriodMs = 20L
      )
      val events = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val firstStarted = new CountDownLatch(1)
      val releaseFirst = new CountDownLatch(1)

      val f1 = fork:
        output.prompt: () =>
          events.add("first-start")
          firstStarted.countDown()
          releaseFirst.await()
          events.add("first-end")
          "A"

      firstStarted.await()
      // The first prompt owns the terminal now. A log write and a status update
      // arriving concurrently must not land on `out`; they defer until resume.
      val sizeDuringPrompt = buf.size()
      output.log("during-first-prompt")
      output.setStatus(Some("stage started"))
      assertEquals(
        buf.size(),
        sizeDuringPrompt,
        "log/setStatus issued while a prompt is open must not write to `out`"
      )

      // A second prompt from another fork must block until the first's
      // bracket has fully closed — asserted structurally via `events`
      // (deterministic: f2 blocks on the semaphore, not on timing).
      val f2 = fork:
        output.prompt: () =>
          events.add("second-start")
          "B"

      releaseFirst.countDown()
      assertEquals(f1.join(), "A")
      assertEquals(f2.join(), "B")

      assertEquals(
        events.asScala.toList,
        List("first-start", "first-end", "second-start"),
        "second prompt's readUser must not start until the first's " +
          "bracket (suspend..resume) has fully closed"
      )

      val drained = buf.toString
      assert(
        drained.contains("during-first-prompt"),
        s"buffered log must be drained on resume; out: $drained"
      )
      assert(
        drained.contains("stage started"),
        s"status label stored during suspend must be redrawn on resume; out: $drained"
      )
      output.close()

  test(
    "a render failure quarantines the terminal listener and leaves the scope running"
  ):
    val failingOut = new PrintStream(new ByteArrayOutputStream()):
      override def print(s: String): Unit = throw new RuntimeException("boom")
    val seen = new java.util.concurrent.ConcurrentLinkedQueue[OrcaEvent]()
    val recorder: OrcaListener = e =>
      val _ = seen.add(e)
    supervised:
      val terminal = TerminalActor.start(
        failingOut,
        useColor = false,
        animated = false,
        workDir = None
      )
      val dispatcher = new EventDispatcher(List(terminal.listener, recorder))
      dispatcher.onEvent(OrcaEvent.Step("first"))
      dispatcher.onEvent(OrcaEvent.Step("second"))
    // Reaching here means the scope ended without the render failure.
    assertEquals(
      seen.asScala.toList,
      List(OrcaEvent.Step("first"), OrcaEvent.Step("second"))
    )
