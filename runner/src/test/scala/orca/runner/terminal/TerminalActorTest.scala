package orca.runner.terminal

import orca.events.OrcaEvent
import ox.channels.BufferCapacity
import ox.{fork, supervised}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.{CompletableFuture, CountDownLatch}
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
        framePeriod = 20.millis
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
        framePeriod = 20.millis
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
        framePeriod = 20.millis
      )
      val events = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val firstStarted = new CountDownLatch(1)
      val releaseFirst = new CountDownLatch(1)

      val f1 = fork:
        output.prompt("first?"): () =>
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
        output.prompt("second?"): () =>
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

  test("nothing logged concurrently lands between a prompt's header and read"):
    val buf = new ByteArrayOutputStream()
    val headerPrinting = new CountDownLatch(1)
    val releaseHeader = new CountDownLatch(1)
    // Blocking the header's print holds the actor between the prompt's header
    // and its suspend — where a concurrent line must not slip in.
    val ps = new PrintStream(buf):
      override def print(s: String): Unit =
        super.print(s)
        if s.contains("first?") then
          headerPrinting.countDown()
          releaseHeader.await()
    supervised:
      val output = TerminalActor.start(
        ps,
        useColor = false,
        animated = false,
        workDir = None
      )
      val prompting = fork:
        output.prompt("first?")(() => buf.toString)
      headerPrinting.await()
      val loggingThread = new CompletableFuture[Thread]()
      val logging = fork:
        val _ = loggingThread.complete(Thread.currentThread())
        output.log("event")
      // Parked on its ask: the line is queued behind the blocked print.
      while loggingThread.get().getState != Thread.State.WAITING do
        Thread.onSpinWait()
      releaseHeader.countDown()
      val seenByRead = prompting.join()
      logging.join()
      assert(
        !seenByRead.contains("event"),
        s"the read must sit directly under its header; out: $seenByRead"
      )

  test("a prompt interrupted while the actor runs its suspend resumes output"):
    val buf = new ByteArrayOutputStream()
    val inSuspend = new CountDownLatch(1)
    val releaseSuspend = new CountDownLatch(1)
    // `suspend` prints the count of the open repeat run; blocking that print
    // holds the actor inside `suspend` while the prompting fork awaits it.
    val ps = new PrintStream(buf):
      override def print(s: String): Unit =
        if s.contains(TerminalOutputState.RepeatGlyph) then
          inSuspend.countDown()
          releaseSuspend.await()
        super.print(s)
    supervised:
      val output = TerminalActor.start(
        ps,
        useColor = false,
        animated = false,
        workDir = None
      )
      output.log("repeated")
      output.log("repeated")
      val promptingThreadRef = new CompletableFuture[Thread]()
      val prompting = fork:
        val _ = promptingThreadRef.complete(Thread.currentThread())
        try
          output.prompt("?")(() => fail("the suspend-ask must be interrupted"))
        catch case _: InterruptedException => ()
      inSuspend.await()
      val promptingThread = promptingThreadRef.get()
      promptingThread.interrupt()
      // `CompletableFuture.get` clears the flag and still returns normally if
      // its result lands before it re-checks, so `suspend` stays blocked until
      // the fork parks again. With the suspend-ask's result pending, its `get`
      // must throw, and the next park is the resume-ask's. Wait for the flag
      // to clear first: the fork was already WAITING before it noticed the
      // interrupt.
      while promptingThread.isInterrupted do Thread.onSpinWait()
      // A fork that ends instead leaves the assertion below to report it.
      while !Set(Thread.State.WAITING, Thread.State.TERMINATED)
          .contains(promptingThread.getState)
      do Thread.onSpinWait()
      releaseSuspend.countDown()
      prompting.join()
      output.log("after")
      assert(
        buf.toString.contains("after"),
        s"output must not stay suspended; out: $buf"
      )

  test("a render failure reaches the caller and leaves the scope running"):
    val failingOut = new PrintStream(new ByteArrayOutputStream()):
      override def print(s: String): Unit = throw new RuntimeException("boom")
    supervised:
      val terminal = TerminalActor.start(
        failingOut,
        useColor = false,
        animated = false,
        workDir = None
      )
      val _ = intercept[RuntimeException]:
        terminal.listener.onEvent(OrcaEvent.Step("first"))
      // A later call finds the actor still running.
      assertEquals(terminal.currentIndent, "")
