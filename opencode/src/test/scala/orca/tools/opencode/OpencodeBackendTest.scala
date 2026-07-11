package orca.tools.opencode

import orca.OrcaFlowException
import orca.backend.StreamSource
import orca.agents.{BackendTag, AgentConfig, Model, SessionId, WireSessionId}
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

  /** Fake [[OpencodeServerHandle]] standing in for the eager server. `http`
    * forces `httpThunk` on every access — a test wanting to assert a spawn
    * never happens passes `fail(...)` as the thunk.
    */
  private class FakeHandle(httpThunk: => OpencodeHttp)
      extends OpencodeServerHandle:
    var closed: Boolean = false
    def http: OpencodeHttp = httpThunk
    def close(): Unit = closed = true

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
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      val result =
        backend.runAutonomous("hi", client, AgentConfig())

      assertEquals(result.output, "done")
      assertEquals(result.model, Some(Model("gpt-4o-mini")))
      // The result reports the WIRE id — the server-minted ses_server1 — while
      // the client→server mapping is recorded in the registry.
      assertEquals(
        result.wireId,
        WireSessionId[BackendTag.Opencode.type]("ses_server1")
      )
      // The turn finalizes through `conv.cancel()` (the self-scoped per-turn
      // `finally`) — but the turn already settled via `session.idle`, so
      // `onCancelRequested`'s settled-gate means NO `/abort` POST
      // fires for this just-idle session, which may be resumed next turn.
      assertEquals(
        http.posts.map(_._1),
        List(
          "/session",
          "/session/ses_server1/prompt_async"
        )
      )
      // The backend forwards the prompt into the prompt_async body.
      val (_, body) = http.posts.find(_._1.endsWith("/prompt_async")).get
      assert(body.contains(""""text":"hi""""), body)

  test(
    "a second call with the same session resumes (one POST /session, two turns)"
  ):
    supervised:
      val http = new FakeHttp(turn("ses_server1", "stop", Nil))
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      val _ =
        backend.runAutonomous("one", client, AgentConfig())
      val _ =
        backend.runAutonomous("two", client, AgentConfig())
      assertEquals(http.posts.count(_._1 == "/session"), 1)
      assertEquals(http.posts.count(_._1.endsWith("/prompt_async")), 2)

  test("registerSession lets a later call resume that server session directly"):
    supervised:
      val http = new FakeHttp(turn("ses_X", "stop", Nil))
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      backend.sessions.register(
        client,
        WireSessionId[BackendTag.Opencode.type]("ses_X")
      )
      val _ =
        backend.runAutonomous("hi", client, AgentConfig())
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
      val backend = new OpencodeBackend(new FakeHandle(http))
      val conv = backend.runInteractive(
        "q",
        fresh,
        "display",
        AgentConfig(),
        outputSchema = Some("""{"type":"object"}""")
      )
      assertEquals(conv.canAskUser, true)
      assertEquals(
        conv.outputSchema,
        Some("""{"type":"object"}""")
      ) // schema threaded through
      conv.events.foreach(_ => ())
      assertEquals(conv.awaitResult().toOption.get.output, "hi")

  test(
    "interactive shell: finally-cancel after a successful turn posts no /abort " +
      "(settledOutcome race regression)"
  ):
    // Mirrors the real driving loop (`AgentCall.runInteractiveOnce`:
    // `try interaction.drive(conversation) finally conversation.cancel()`):
    // the reader settles on its own fork/thread while `cancel()` runs here, on
    // the test's thread — a genuine cross-thread race on `isSettled`, not just
    // a same-thread call sequence. Before `settledOutcome` was `@volatile`,
    // `cancel()` could observe a stale `isSettled == false` on this path and
    // fire the real `/abort` on a turn that already succeeded.
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
      val backend = new OpencodeBackend(new FakeHandle(http))
      val conv = backend.runInteractive(
        "q",
        fresh,
        "display",
        AgentConfig(),
        outputSchema = None
      )
      try
        conv.events.foreach(_ => ())
        assertEquals(conv.awaitResult().toOption.get.output, "hi")
      finally conv.cancel()
      assert(
        !http.posts.exists(_._1.endsWith("/abort")),
        s"unexpected /abort POST after a settled turn: ${http.posts}"
      )

  test("close() delegates to the server handle"):
    val http = new FakeHttp(Nil, _ => 200)
    val handle = new FakeHandle(http)
    val backend = new OpencodeBackend(handle)
    backend.close()
    assert(handle.closed, "backend.close() must close the server handle")

  test(
    "willContinue never spawns the server when there is no client→server " +
      "mapping (the no-spurious-spawn guarantee)"
  ):
    supervised:
      // No `register`/turn has mapped this client id, so `exists` must
      // short-circuit on the registry gate WITHOUT forcing `http` — the fake
      // handle fails the test if it is ever forced.
      val backend = new OpencodeBackend(
        new FakeHandle(fail("must not spawn"))
      )
      val client = fresh
      assert(!backend.sessions.willContinue(client))

  test(
    "a probe with a rehydrated wire id spawns the server and returns its answer"
  ):
    supervised:
      // Mirrors resume: FlowLifecycle.rehydrateSessions registers the
      // client→server mapping before any turn has touched the server, so
      // `http` has never been forced yet when `exists` is called. The probe
      // must still force the (lazy) spawn and contact the fresh server rather
      // than short-circuiting on whether it was already running — that
      // short-circuit was the cross-restart-resume bug this pins against a
      // regression of.
      val http = new FakeHttp(
        Nil,
        path => if path == "/session/ses_server1" then 200 else 404
      )
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      backend.sessions.register(
        client,
        WireSessionId[BackendTag.Opencode.type]("ses_server1")
      )
      assert(backend.sessions.willContinue(client))

  test(
    "willContinue returns false when there is no client→server mapping"
  ):
    supervised:
      // Server started (would answer 200), but the probed client id was never
      // mapped to a server id, so the probe must not run on the client id.
      val existingId = "ses_server1"
      val http = new FakeHttp(
        turn(existingId, "stop", Nil),
        path => if path == s"/session/$existingId" then 200 else 404
      )
      val backend = new OpencodeBackend(new FakeHandle(http))
      val _ =
        backend.runAutonomous("hi", fresh, AgentConfig())
      // A different, unmapped client id resolves to no server id → false.
      assert(!backend.sessions.willContinue(fresh))

  test("probeSession returns true when getStatus is 200"):
    supervised:
      val http = new FakeHttp(
        Nil,
        path => if path == "/session/ses_abc" then 200 else 404
      )
      val backend = new OpencodeBackend(new FakeHandle(http))
      assert(backend.probeSession("ses_abc", http))

  test("probeSession returns false when getStatus is 404"):
    supervised:
      val http = new FakeHttp(Nil, _ => 404)
      val backend = new OpencodeBackend(new FakeHandle(http))
      assert(!backend.probeSession("ses_missing", http))

  test(
    "willContinue probes the SERVER id: true after a turn maps client→server"
  ):
    supervised:
      val existingId = "ses_server1"
      val http = new FakeHttp(
        turn(existingId, "stop", Nil),
        path => if path == s"/session/$existingId" then 200 else 404
      )
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      // A real turn maps client → ses_server1 in the registry.
      val _ =
        backend.runAutonomous("hi", client, AgentConfig())
      // Probing the CLIENT id resolves to the server id, which the server has.
      assert(backend.sessions.willContinue(client))

  test(
    "willContinue returns false when the mapped server id is unknown to the server"
  ):
    supervised:
      val http = new FakeHttp(turn("ses_server1", "stop", Nil), _ => 404)
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      val _ =
        backend.runAutonomous("hi", client, AgentConfig())
      // client → ses_server1 is mapped, but the server now 404s for it.
      assert(!backend.sessions.willContinue(client))

  test(
    "probeSession returns false when getStatus throws (verifies NonFatal catch)"
  ):
    supervised:
      val http = new FakeHttp(Nil):
        override def getStatus(path: String): Int =
          throw new java.io.IOException("connection refused")
      val backend = new OpencodeBackend(new FakeHandle(http))
      assert(!backend.probeSession("ses_abc", http))

  test(
    "willContinue returns false for a malicious mapped server id (slashes)"
  ):
    supervised:
      val http = new FakeHttp(Nil, _ => 200) // would return 200 if called
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      // Even if the registry maps to a malicious server id, the guard blocks it.
      backend.sessions.register(
        client,
        WireSessionId[BackendTag.Opencode.type]("a/b")
      )
      assert(!backend.sessions.willContinue(client))

  test(
    "willContinue returns false for a malicious mapped server id (query/fragment chars)"
  ):
    supervised:
      val http = new FakeHttp(Nil, _ => 200)
      val backend = new OpencodeBackend(new FakeHandle(http))
      val client = fresh
      backend.sessions.register(
        client,
        WireSessionId[BackendTag.Opencode.type]("x?y#z")
      )
      assert(!backend.sessions.willContinue(client))

  test(
    "a session-creation failure never opens the SSE stream (open-path leak)"
  ):
    supervised:
      var eventsOpened = false
      val http = new FakeHttp(Nil):
        override def events(): StreamSource =
          eventsOpened = true
          super.events()
        override def postJson(path: String, body: String): String =
          if path == "/session" then
            throw new OrcaFlowException("boom: session create failed")
          else super.postJson(path, body)
      val backend = new OpencodeBackend(new FakeHandle(http))
      val _ = intercept[OrcaFlowException]:
        backend.runAutonomous("hi", fresh, AgentConfig())
      assert(
        !eventsOpened,
        "GET /event must not open when POST /session throws"
      )
