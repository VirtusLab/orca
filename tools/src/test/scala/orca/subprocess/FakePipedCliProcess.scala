package orca.subprocess

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

/** Test double for `PipedCliProcess`. Tests push stdout lines via
  * `enqueueStdout` (optionally in response to observed writes) and read
  * what the subject wrote to stdin via `writes`. Both queues are
  * thread-safe so a test can drive the subject from one thread while
  * asserting on another.
  *
  * `stdoutLines` blocks on read, same as the real piped process; tests
  * that might deadlock a driver should call `closeStdout()` to signal
  * EOF.
  */
class FakePipedCliProcess(
    initiallyAlive: Boolean = true
) extends PipedCliProcess:

  private val alive = new AtomicBoolean(initiallyAlive)
  private val sigintCount = new AtomicInteger(0)
  private val stdinLines: AtomicReference[List[String]] =
    new AtomicReference(Nil)
  private val stdoutQueue = new LinkedBlockingQueue[Option[String]]()
  private val stderrQueue = new LinkedBlockingQueue[Option[String]]()
  private val stdinClosed = new AtomicBoolean(false)

  def sendSigInt(): Unit =
    val _ = sigintCount.incrementAndGet()
    alive.set(false)
    closeStdout()
    closeStderr()

  def isAlive: Boolean = alive.get()

  def waitForExit(): Int = 0

  def writeLine(json: String): Unit =
    val _ = stdinLines.updateAndGet(ls => json :: ls)

  def closeStdin(): Unit = stdinClosed.set(true)

  def stdoutLines: Iterator[String] = blockingIterator(stdoutQueue)

  def stderrLines: Iterator[String] = blockingIterator(stderrQueue)

  // --- Test-only controls ---

  /** Push a line that the next call to `stdoutLines.next()` will receive. */
  def enqueueStdout(line: String): Unit =
    val _ = stdoutQueue.offer(Some(line))

  def enqueueStderr(line: String): Unit =
    val _ = stderrQueue.offer(Some(line))

  /** Signal EOF to anyone iterating `stdoutLines`. */
  def closeStdout(): Unit =
    val _ = stdoutQueue.offer(None)

  def closeStderr(): Unit =
    val _ = stderrQueue.offer(None)

  /** Lines written to stdin, in write order. */
  def writes: List[String] = stdinLines.get().reverse

  def sigIntCount: Int = sigintCount.get()

  def isStdinClosed: Boolean = stdinClosed.get()

  private def blockingIterator(
      q: LinkedBlockingQueue[Option[String]]
  ): Iterator[String] =
    new Iterator[String]:
      private val next0 = new AtomicReference[Option[String]](null)

      def hasNext: Boolean =
        if next0.get() == null then
          // `take()` blocks until something lands; Some means a line,
          // None means EOF.
          next0.set(q.take())
        next0.get().isDefined

      def next(): String =
        if !hasNext then throw new NoSuchElementException("stream closed")
        val value = next0.get().get
        next0.set(null)
        value
