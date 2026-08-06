package orca.backend.mcp

import chimp.tool
import io.circe.Codec
import ox.supervised
import sttp.tapir.Schema

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

private case class Probe(text: String) derives Codec, Schema

class McpHostTest extends munit.FunSuite:

  /** What a tool with this logic returns once bound through [[McpHost]] — the
    * guard, not the handler, is what these tests are about.
    */
  private def resultOf(
      logic: Probe => Either[String, String]
  ): Either[String, String] =
    McpHost
      .guarded(tool("probe").input[Probe].handle(logic))
      .logic(Probe(""), Nil)

  test("a result of exactly the cap passes through whole"):
    val text = "x" * McpHost.MaxOutputChars
    assertEquals(resultOf(_ => Right(text)), Right(text))

  test("a result one char over the cap is cut, and names the cut"):
    val out =
      resultOf(_ => Right("x" * (McpHost.MaxOutputChars + 1))).toOption.get
    assert(out.startsWith("x" * McpHost.MaxOutputChars), out.take(80))
    assert(out.endsWith("characters — narrow the request]"), out.takeRight(80))

  test("an error over the cap is cut too: it costs the same tokens"):
    val out =
      resultOf(_ => Left("x" * (McpHost.MaxOutputChars + 1))).left.toOption.get
    assert(out.endsWith("characters — narrow the request]"), out.takeRight(80))

  test("a handler that throws returns a tool error rather than escaping"):
    assertEquals(
      resultOf(_ => throw new RuntimeException("gh exploded")),
      Left("gh exploded")
    )

  test("a bound tool answers a throwing call over the wire, cut to the cap"):
    // The one thing the tests above cannot see: that `start` puts this guard in
    // front of what it binds. Without the catch a throwing handler reaches
    // Netty and the agent gets a transport failure it cannot read; an
    // oversized message asserts the same call carries the cap, so wiring some
    // other guard in would not satisfy this.
    supervised:
      val message = "gh exploded " + "x" * McpHost.MaxOutputChars
      val throwing =
        tool("probe")
          .input[Probe]
          .handle(_ => throw new RuntimeException(message))
      val host = McpHost.start(List(throwing), 10.seconds)
      val response = call(
        host.url,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
          """"params":{"name":"probe","arguments":{"text":""}}}"""
      )
      assertEquals(response.statusCode(), 200, response.body().take(200))
      assert(response.body().contains("gh exploded"), response.body().take(200))
      // The marker's own dash is non-ASCII, so match the part that survives
      // whatever charset the response is decoded with.
      assert(
        response.body().contains(s"cut after ${McpHost.MaxOutputChars} char"),
        response.body().takeRight(200)
      )

  /** POST one JSON-RPC message to a running host. The client is closed rather
    * than left to a finalizer: its idle keep-alive connection otherwise holds
    * the binding open, and `stop()` waits ten seconds for it.
    */
  private def call(url: String, rpc: String): HttpResponse[String] =
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(rpc))
      .build()
    val client = HttpClient.newHttpClient()
    try client.send(request, HttpResponse.BodyHandlers.ofString())
    finally client.close()
