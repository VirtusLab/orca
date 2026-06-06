package orca.tools.opencode

import orca.backend.StreamSource
import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import ox.supervised

class OpencodeBackendTest extends munit.FunSuite:

  /** Serves a canned turn over SSE and records POSTs. `events` hands back the
    * same canned stream each call.
    */
  private class FakeHttp(sse: List[String]) extends OpencodeHttp:
    var posts: List[(String, String)] = Nil
    def postJson(path: String, body: String): String =
      posts = posts :+ (path -> body)
      if path == "/session" then """{"id":"ses_server1"}""" else ""
    def events(): StreamSource = new StreamSource:
      def lines: Iterator[String] = sse.iterator
      def errorLines: Iterator[String] = Iterator.empty
      def interrupt(): Unit = ()
      def tryExitCode: Option[Int] = Some(0)

  private def data(json: String): String = s"data: $json"

  private def turn(
      sessionId: String,
      finish: String,
      extra: List[String]
  ): List[String] =
    extra ++ List(
      data(
        s"""{"type":"message.updated","properties":{"info":{"role":"assistant","sessionID":"$sessionId","modelID":"gpt-4o-mini","finish":"$finish"}}}"""
      ),
      data(
        s"""{"type":"session.idle","properties":{"sessionID":"$sessionId"}}"""
      )
    )

  private def fresh = SessionId.fresh[BackendTag.Opencode.type]

  test(
    "runAutonomous creates a session, fires prompt_async, returns the result"
  ):
    supervised:
      val http = new FakeHttp(
        turn(
          "ses_server1",
          "stop",
          List(
            data(
              """{"type":"message.part.delta","properties":{"sessionID":"ses_server1","field":"text","delta":"done"}}"""
            )
          )
        )
      )
      val backend = new OpencodeBackend(_ => http)
      val client = fresh
      val result =
        backend.runAutonomous("hi", client, LlmConfig.default, os.temp.dir())

      assertEquals(result.output, "done")
      assertEquals(result.model, Some(Model("gpt-4o-mini")))
      // The caller's id stays the handle; the server id is hidden.
      assertEquals(result.sessionId, client)
      assertEquals(
        http.posts.map(_._1),
        List("/session", "/session/ses_server1/prompt_async")
      )
      // The backend forwards the prompt into the prompt_async body.
      val (_, body) = http.posts.find(_._1.endsWith("/prompt_async")).get
      assert(body.contains(""""text":"hi""""), body)

  test(
    "a second call with the same session resumes (one POST /session, two turns)"
  ):
    supervised:
      val http = new FakeHttp(turn("ses_server1", "stop", Nil))
      val backend = new OpencodeBackend(_ => http)
      val client = fresh
      val _ =
        backend.runAutonomous("one", client, LlmConfig.default, os.temp.dir())
      val _ =
        backend.runAutonomous("two", client, LlmConfig.default, os.temp.dir())
      assertEquals(http.posts.count(_._1 == "/session"), 1)
      assertEquals(http.posts.count(_._1.endsWith("/prompt_async")), 2)

  test("registerSession lets a later call resume that server session directly"):
    supervised:
      val http = new FakeHttp(turn("ses_X", "stop", Nil))
      val backend = new OpencodeBackend(_ => http)
      val client = fresh
      backend.registerSession(
        client,
        SessionId[BackendTag.Opencode.type]("ses_X")
      )
      val _ =
        backend.runAutonomous("hi", client, LlmConfig.default, os.temp.dir())
      assertEquals(
        http.posts.count(_._1 == "/session"),
        0
      ) // resumed, not created
      assert(http.posts.exists(_._1 == "/session/ses_X/prompt_async"))

  test("runInteractive returns a live conversation that can ask the user"):
    supervised:
      val http = new FakeHttp(
        turn(
          "ses_server1",
          "stop",
          List(
            data(
              """{"type":"message.part.delta","properties":{"sessionID":"ses_server1","field":"text","delta":"hi"}}"""
            )
          )
        )
      )
      val backend = new OpencodeBackend(_ => http)
      val conv = backend.runInteractive(
        "q",
        fresh,
        "display",
        LlmConfig.default,
        os.temp.dir(),
        outputSchema = Some("""{"type":"object"}""")
      )
      assertEquals(conv.canAskUser, true)
      assertEquals(
        conv.outputSchema,
        Some("""{"type":"object"}""")
      ) // schema threaded through
      conv.events.foreach(_ => ())
      assertEquals(conv.awaitResult().toOption.get.output, "hi")
