package orca.backend.mcp

import chimp.protocol.ToolContent
import chimp.server.{NoStructuredOutput, ServerContext, ToolResult, tool}
import io.circe.Codec
import io.circe.parser.parse
import orca.effect.Network
import orca.json.{JsonData, SchemaDerivation}
import orca.mcp
import ox.supervised
import sttp.shared.Identity
import sttp.tapir.Schema

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

private case class Probe(text: String) derives Codec

private object Probe:
  given Schema[Probe] = SchemaDerivation.derived

private case class Count(n: Int) derives JsonData

class McpHostTest extends munit.FunSuite:

  /** What a tool with this logic returns once bound through [[McpHost]] — the
    * guard, not the handler, is what these tests are about.
    */
  private def resultOf(
      logic: Probe => ToolResult[NoStructuredOutput]
  ): ToolResult[NoStructuredOutput] =
    McpHost
      .guarded(tool("probe").input[Probe].handle(logic))
      .logic(Probe(""), ServerContext.noop[Identity], Nil)

  private def texts(result: ToolResult[?]): List[String] =
    result.content.collect:
      case t: ToolContent.Text => t.text

  test("a result of exactly the cap passes through whole"):
    val text = "x" * McpHost.MaxOutputChars
    val result = resultOf(_ => ToolResult.text(text))
    assert(!result.isError)
    assertEquals(texts(result), List(text))

  test("a result one char over the cap is cut, and names the cut"):
    val out =
      texts(
        resultOf(_ => ToolResult.text("x" * (McpHost.MaxOutputChars + 1)))
      ).head
    assert(out.startsWith("x" * McpHost.MaxOutputChars), out.take(80))
    assert(out.endsWith("characters — narrow the request]"), out.takeRight(80))

  test("an error over the cap is cut too: it costs the same tokens"):
    val result =
      resultOf(_ => ToolResult.error("x" * (McpHost.MaxOutputChars + 1)))
    assert(result.isError)
    val out = texts(result).head
    assert(out.endsWith("characters — narrow the request]"), out.takeRight(80))

  test("a handler that throws returns a tool error rather than escaping"):
    val result = resultOf(_ => throw new RuntimeException("gh exploded"))
    assert(result.isError)
    assertEquals(texts(result), List("gh exploded"))

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
      val host = McpHost.start(Network.ambient, List(throwing), 10.seconds)
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

  test("input that fails to decode gets a one-line JSON-RPC error"):
    // chimp answers a decode failure with an `Invalid arguments` error, not a
    // tool result; without the facade's decoder its message would carry
    // jsoniter's multi-line buffer dump.
    supervised:
      val counting = mcp.tool[Count]("count", "")(c => mcp.text(c.n.toString))
      val host = McpHost.start(Network.ambient, List(counting), 10.seconds)
      val response = call(
        host.url,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
          """"params":{"name":"count","arguments":{"n":"7"}}}"""
      )
      assertEquals(response.statusCode(), 200, response.body())
      val error = parse(response.body()).toOption.get.hcursor.downField("error")
      assertEquals(error.get[Int]("code"), Right(-32602), response.body())
      val message = error.get[String]("message").toOption.get
      assert(message.contains("illegal number"), message)
      assert(!message.contains('\n'), message)

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
