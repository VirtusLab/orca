package orca.tools.claude.mcp

import ox.supervised
import ox.channels.BufferCapacity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

class AskUserMcpServerTest extends munit.FunSuite:

  /** Smoke test: spin up the server, send a `tools/list` JSON-RPC request,
    * assert the `ask_user` tool is advertised in the response.
    *
    * No bridge interaction — the handler isn't invoked here, just the
    * tools-discovery path. Bridge-driven end-to-end behaviour belongs in a
    * higher-level integration test once the Claude wiring lands.
    */
  test("tools/list advertises ask_user"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge
      val server = AskUserMcpServer.start(bridge)

      val rpc =
        """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
      val client = HttpClient.newHttpClient()
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(server.url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(rpc))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      assertEquals(resp.statusCode(), 200)
      assert(
        resp.body().contains("ask_user"),
        s"expected the response to advertise ask_user; got: ${resp.body()}"
      )
