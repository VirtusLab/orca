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
