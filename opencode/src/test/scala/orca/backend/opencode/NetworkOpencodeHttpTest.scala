package orca.backend.opencode

import orca.OrcaFlowException
import orca.effect.{HttpClient, HttpLines, HttpReply}

import java.io.IOException
import scala.concurrent.duration.FiniteDuration

class NetworkOpencodeHttpTest extends munit.FunSuite:

  /** Answers each GET with the next of `gets`, and every POST with `post`. */
  private class FakeClient(
      gets: List[() => HttpReply],
      post: HttpReply = HttpReply(200, "")
  ) extends HttpClient:
    private var remaining = gets
    var getPaths: List[String] = Nil
    var closed = false
    def get(path: String, timeout: FiniteDuration): HttpReply =
      getPaths = getPaths :+ path
      val next = remaining.head
      remaining = remaining.tail
      next()
    def postJson(
        path: String,
        json: String,
        timeout: FiniteDuration
    ): HttpReply =
      post
    def openLines(path: String): HttpLines = new HttpLines:
      def lines: Iterator[String] = Iterator("data: x")
      def close(): Unit = closed = true

  private val healthy: () => HttpReply = () => HttpReply(200, "")

  test("connect polls /doc until the server answers 200"):
    val client = new FakeClient(
      List(
        () => throw IOException("refused"),
        () => HttpReply(503, ""),
        healthy
      )
    )
    val _ = NetworkOpencodeHttp.connect(client, "http://x")
    assertEquals(client.getPaths, List("/doc", "/doc", "/doc"))

  test("postJson returns the body of a 2xx reply"):
    val client = new FakeClient(List(healthy), post = HttpReply(201, "made"))
    val http = NetworkOpencodeHttp.connect(client, "http://x")
    assertEquals(http.postJson("/session", "{}"), "made")

  test("postJson throws on a non-2xx reply, naming the status"):
    val client = new FakeClient(List(healthy), post = HttpReply(500, "boom"))
    val http = NetworkOpencodeHttp.connect(client, "http://x")
    val e = intercept[OrcaFlowException](http.postJson("/session", "{}"))
    assert(e.getMessage.contains("500 boom"), e.getMessage)

  test("getStatus is 0 when the request fails"):
    val client = new FakeClient(
      List(healthy, () => throw IOException("reset"))
    )
    val http = NetworkOpencodeHttp.connect(client, "http://x")
    assertEquals(http.getStatus("/session/s"), 0)

  test("interrupting the event stream closes it"):
    val client = new FakeClient(List(healthy))
    val events = NetworkOpencodeHttp.connect(client, "http://x").events()
    events.interrupt()
    assert(client.closed)
