package orca.effect

import ox.{CancellableFork, Ox, forkCancellable, supervised, timeout}
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import java.io.{IOException, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt

class HttpClientTest extends munit.FunSuite:

  private val whoami =
    endpoint.get
      .in("whoami")
      .in(header[String]("Authorization"))
      .out(stringBody)
      .handleSuccess(identity)

  private val echo =
    endpoint.post
      .in("echo")
      .in(header[String]("Content-Type"))
      .in(stringBody)
      .out(stringBody)
      .handleSuccess((contentType, body) => s"$contentType $body")

  private val down =
    endpoint.get
      .in("down")
      .errorOut(statusCode(StatusCode.ServiceUnavailable).and(stringBody))
      .handle(_ => Left("down"))

  /** A body that sends one line and the start of a second, then blocks until
    * `release`.
    */
  private final class HangingBody extends InputStream:
    private val released = new CountDownLatch(1)
    private var sent = false
    def release(): Unit = released.countDown()

    // tapir's netty server reads with `readNBytes` and ends the body at a short
    // read, so the first chunk is filled.
    override def readNBytes(len: Int): Array[Byte] =
      if !sent then
        sent = true
        ("ready\n" + "x" * (len - 6)).getBytes(UTF_8)
      else
        released.await()
        Array.emptyByteArray

    def read(): Int = throw UnsupportedOperationException()

  private def withClient[T](endpoints: List[ServerEndpoint[Any, Identity]])(
      body: Ox ?=> HttpClient => T
  ): T =
    supervised:
      val net = Network.ambient
      val port = net.serveLocal(endpoints, 5.seconds)
      body(net.httpClient(s"http://127.0.0.1:$port", "user", "secret"))

  test("get returns the status and body, sending Basic auth"):
    val reply = withClient(List(whoami))(_.get("/whoami", 5.seconds))
    assertEquals(reply, HttpReply(200, "Basic dXNlcjpzZWNyZXQ="))

  test("postJson sends the body as JSON"):
    val reply =
      withClient(List(echo))(_.postJson("/echo", """{"a":1}""", 5.seconds))
    assertEquals(reply, HttpReply(200, """application/json {"a":1}"""))

  test("a non-2xx status is returned, not thrown"):
    val reply = withClient(List(down))(_.get("/down", 5.seconds))
    assertEquals(reply, HttpReply(503, "down"))
    assert(!reply.isSuccess)

  /** Opens a hanging stream, reads its first line in a fork, waits until that
    * fork is parked in the next read, then runs `unblock` on it and returns how
    * the read ended.
    */
  private def unblockParkedReader(
      unblock: (HttpLines, CancellableFork[Boolean]) => Either[
        Throwable,
        Boolean
      ]
  ): Either[Throwable, Boolean] =
    val hanging = new HangingBody
    val stream =
      endpoint.get.in("stream").out(inputStreamBody).handleSuccess(_ => hanging)
    withClient(List(stream)): client =>
      val lines = client.openLines("/stream")
      try
        // Completing never parks the reader, so its next park is the read.
        val readerThread = new CompletableFuture[Thread]
        val reader = forkCancellable:
          val it = lines.lines
          assertEquals(it.next(), "ready")
          readerThread.complete(Thread.currentThread()): Unit
          it.hasNext
        awaitParked(readerThread.get(5, TimeUnit.SECONDS))
        timeout(5.seconds)(unblock(lines, reader))
      finally
        lines.close()
        // Before the scope ends: the server's stop waits for the body.
        hanging.release()

  /** A parked virtual thread reports `WAITING`; bounded, so a reader that never
    * parks fails the test instead of hanging it.
    */
  private def awaitParked(thread: Thread): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while thread.getState != Thread.State.WAITING do
      assert(
        System.nanoTime() < deadline,
        s"reader never parked: ${thread.getState}"
      )
      Thread.onSpinWait()

  test("openLines: close unblocks a reader parked in the body read"):
    val outcome = unblockParkedReader: (lines, reader) =>
      lines.close()
      scala.util.Try(reader.join()).toEither
    assert(outcome.left.exists(_.isInstanceOf[IOException]), outcome)

  test("openLines: interrupting a reader parked in the body read unblocks it"):
    val outcome = unblockParkedReader((_, reader) => reader.cancel())
    assert(
      outcome.left.exists(_.getCause.isInstanceOf[InterruptedException]),
      outcome
    )
