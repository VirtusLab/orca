package orca.subprocess

import scala.concurrent.duration.DurationInt
import java.util.concurrent.{Executors, TimeUnit}

class FakePipedCliProcessTest extends munit.FunSuite:

  test("writeLine records outbound stdin lines in order"):
    val p = new FakePipedCliProcess()
    p.writeLine("first")
    p.writeLine("second")
    assertEquals(p.writes, List("first", "second"))

  test("stdoutLines yields enqueued values and ends on closeStdout"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("one")
    p.enqueueStdout("two")
    p.closeStdout()
    val seen = p.stdoutLines.toList
    assertEquals(seen, List("one", "two"))

  test("stdoutLines blocks until a line arrives"):
    val p = new FakePipedCliProcess()
    val pool = Executors.newSingleThreadExecutor()
    try
      val future = pool.submit: () =>
        p.stdoutLines.next()
      // No line yet — the future should not complete.
      assert(
        !future.isDone,
        "stdoutLines.next() should block when the queue is empty"
      )
      p.enqueueStdout("arrived")
      val result = future.get(500, TimeUnit.MILLISECONDS)
      assertEquals(result, "arrived")
    finally pool.shutdownNow()

  test("sendSigInt marks dead and closes both output streams"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("before")
    p.sendSigInt()
    assert(!p.isAlive)
    assertEquals(p.sigIntCount, 1)
    assertEquals(p.stdoutLines.toList, List("before"))
    assertEquals(p.stderrLines.toList, List.empty)

  test("closeStdin records the signal without affecting stdout iteration"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("x")
    p.closeStdin()
    p.closeStdout()
    assert(p.isStdinClosed)
    assertEquals(p.stdoutLines.toList, List("x"))

  test("stdout and stderr iterate independently"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("out-1")
    p.enqueueStderr("err-1")
    p.enqueueStdout("out-2")
    p.closeStdout()
    p.closeStderr()
    assertEquals(p.stdoutLines.toList, List("out-1", "out-2"))
    assertEquals(p.stderrLines.toList, List("err-1"))

  test("writes after closeStdin are still recorded — test fake is lenient"):
    val p = new FakePipedCliProcess()
    p.closeStdin()
    p.writeLine("should-still-record")
    assertEquals(p.writes, List("should-still-record"))

  test("tryExitCode reports None while alive, Some after sigInt"):
    val p = new FakePipedCliProcess()
    assertEquals(p.tryExitCode, None)
    p.sendSigInt()
    assertEquals(p.tryExitCode, Some(0))
    p.sendSigInt() // idempotent — still dead, still Some
    assertEquals(p.tryExitCode, Some(0))
    assertEquals(p.sigIntCount, 2)
