package orca.backend.mcp

import ox.{forkDiscard, supervised}
import ox.channels.BufferCapacity

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

class AskUserMcpServerTest extends munit.FunSuite:

  /** Smoke test of the tools-discovery path: a `tools/list` JSON-RPC request
    * must advertise `ask_user`. No bridge interaction — the handler isn't
    * invoked here.
    */
  test("tools/list advertises ask_user"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val bridge = new AskUserBridge
      val server = AskUserMcpServer.start(bridge)

      val resp = post(
        server.url,
        """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
      )
      assertEquals(resp.statusCode(), 200)
      assert(
        resp.body().contains("ask_user"),
        s"expected the response to advertise ask_user; got: ${resp.body()}"
      )

  test("the scope ends while an ask_user call is still waiting for an answer"):
    // The handler blocks on the bridge with no one to answer; only the scope
    // interrupting it lets the scope finish.
    val turn = Thread
      .ofVirtual()
      .start: () =>
        supervised:
          given BufferCapacity = BufferCapacity(8)
          val session = AskUserSession.allocate()
          forkDiscard:
            post(
              session.server.url,
              """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                """"params":{"name":"ask_user","arguments":{"question":"q?"}}}"""
            )
          val _ = session.bridge.nextQuestion()
    assert(turn.join(Duration.ofSeconds(10)), "the scope never ended")

  private def post(url: String, rpc: String): HttpResponse[String] =
    HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(rpc))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
