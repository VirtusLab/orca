package orca.tools.opencode

import orca.backend.StreamSource
import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import ox.supervised

class OpencodeBackendTest extends munit.FunSuite:

  /** Serves a canned turn over SSE and records POSTs. `events` hands back the
    * same canned stream each call. `statusFor` controls what `getStatus`
    * returns for each path prefix: defaults to 404 for unknown paths.
    */
  private class FakeHttp(
      sse: List[String],
      statusFor: String => Int = _ => 404
  ) extends OpencodeHttp:
    var posts: List[(String, String)] = Nil
    def postJson(path: String, body: String): String =
      posts = posts :+ (path -> body)
      if path == "/session" then """{"id":"ses_server1"}""" else ""
    def events(): StreamSource = new StreamSource:
      def lines: Iterator[String] = sse.iterator
      def errorLines: Iterator[String] = Iterator.empty
      def interrupt(): Unit = ()
      def tryExitCode: Option[Int] = Some(0)
    override def getStatus(path: String): Int = statusFor(path)

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

  test("sessionExists returns false when the server has not been started yet"):
    supervised:
      val http = new FakeHttp(Nil, _ => 200)
      val backend = new OpencodeBackend(_ => http)
      // No runAutonomous call — firstWorkDir is still null.
      assert(!backend.sessionExists(fresh))

  test("probeSession returns true when getStatus is 200"):
    supervised:
      val http = new FakeHttp(
        Nil,
        path => if path == "/session/ses_abc" then 200 else 404
      )
      val backend = new OpencodeBackend(_ => http)
      assert(backend.probeSession("ses_abc", http))

  test("probeSession returns false when getStatus is 404"):
    supervised:
      val http = new FakeHttp(Nil, _ => 404)
      val backend = new OpencodeBackend(_ => http)
      assert(!backend.probeSession("ses_missing", http))

  test("sessionExists returns true after server is started and session exists"):
    supervised:
      val existingId = "ses_server1"
      val http = new FakeHttp(
        turn(existingId, "stop", Nil),
        path => if path == s"/session/$existingId" then 200 else 404
      )
      val backend = new OpencodeBackend(_ => http)
      val client = fresh
      // Trigger server init via a real turn.
      val _ =
        backend.runAutonomous("hi", client, LlmConfig.default, os.temp.dir())
      // Now sessionExists can probe the live server.
      val knownSid = SessionId[BackendTag.Opencode.type](existingId)
      assert(backend.sessionExists(knownSid))

  test(
    "sessionExists returns false after server is started but session is unknown"
  ):
    supervised:
      val http = new FakeHttp(turn("ses_server1", "stop", Nil), _ => 404)
      val backend = new OpencodeBackend(_ => http)
      val client = fresh
      val _ =
        backend.runAutonomous("hi", client, LlmConfig.default, os.temp.dir())
      assert(
        !backend.sessionExists(
          SessionId[BackendTag.Opencode.type]("ses_unknown")
        )
      )

  test(
    "probeSession returns false when getStatus throws (verifies NonFatal catch)"
  ):
    supervised:
      val http = new FakeHttp(Nil):
        override def getStatus(path: String): Int =
          throw new java.io.IOException("connection refused")
      val backend = new OpencodeBackend(_ => http)
      assert(!backend.probeSession("ses_abc", http))

  test("sessionExists returns false for a malicious id with slashes"):
    supervised:
      val http = new FakeHttp(Nil, _ => 200) // would return 200 if called
      val backend = new OpencodeBackend(_ => http)
      val malicious = SessionId[BackendTag.Opencode.type]("a/b")
      assert(!backend.sessionExists(malicious))

  test(
    "sessionExists returns false for a malicious id with query/fragment chars"
  ):
    supervised:
      val http = new FakeHttp(Nil, _ => 200)
      val backend = new OpencodeBackend(_ => http)
      val malicious = SessionId[BackendTag.Opencode.type]("x?y#z")
      assert(!backend.sessionExists(malicious))
