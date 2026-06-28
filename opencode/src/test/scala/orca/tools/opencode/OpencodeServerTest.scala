package orca.tools.opencode

import orca.OrcaFlowException
import orca.backend.StreamSource
import orca.subprocess.{
  CliResult,
  CliRunner,
  FakePipedCliProcess,
  PipedCliProcess
}
import ox.supervised

import java.util.concurrent.atomic.AtomicInteger

class OpencodeServerTest extends munit.FunSuite:

  test("parseBaseUrl extracts the URL from the listening line"):
    assertEquals(
      OpencodeServer.parseBaseUrl(
        "opencode server listening on http://127.0.0.1:4096"
      ),
      Some("http://127.0.0.1:4096")
    )
    assertEquals(OpencodeServer.parseBaseUrl("starting up"), None)

  private class RecordingRunner(process: PipedCliProcess) extends CliRunner:
    val spawns = new AtomicInteger(0)
    var lastArgs: Seq[String] = Nil
    var lastEnv: Map[String, String] = Map.empty
    def run(
        args: Seq[String],
        stdin: String,
        env: Map[String, String],
        cwd: os.Path
    ): CliResult = throw new UnsupportedOperationException
    def spawnPiped(
        args: Seq[String],
        env: Map[String, String],
        cwd: os.Path,
        pipeStderr: Boolean
    ): PipedCliProcess =
      val _ = spawns.incrementAndGet()
      lastArgs = args
      lastEnv = env
      process

  private def listeningProcess: FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout("opencode server listening on http://127.0.0.1:4096")
    p.closeStdout()
    p.closeStderr()
    p

  test("lazy start: spawns serve once, reads the URL, hands it to the client"):
    supervised:
      val runner = new RecordingRunner(listeningProcess)
      var built: Option[(String, String)] = None
      val stub = new OpencodeHttp:
        def postJson(path: String, body: String): String = "ok"
        def events(): StreamSource =
          throw new UnsupportedOperationException
      val server = new OpencodeServer(
        runner,
        os.temp.dir(),
        httpFor = (url, pwd) =>
          built = Some(url -> pwd)
          stub
      )

      assertEquals(runner.spawns.get(), 0) // nothing spawned until http forced
      assertEquals(server.http.postJson("/x", "{}"), "ok")

      assertEquals(
        runner.lastArgs,
        Seq("opencode", "serve", "--port", "0", "--log-level", "WARN")
      )
      assert(runner.lastEnv.contains("OPENCODE_SERVER_PASSWORD"))
      assertEquals(built.map(_._1), Some("http://127.0.0.1:4096"))

      val _ = server.http.postJson("/y", "{}") // reuse: no second spawn
      assertEquals(runner.spawns.get(), 1)

  test("a custom launcher wraps the serve argv at spawn"):
    supervised:
      val runner = new RecordingRunner(listeningProcess)
      val stub = new OpencodeHttp:
        def postJson(path: String, body: String): String = "ok"
        def events(): StreamSource =
          throw new UnsupportedOperationException
      val server = new OpencodeServer(
        runner,
        os.temp.dir(),
        launcher = OpencodeLauncher.ollama("qwen3-coder"),
        httpFor = (_, _) => stub
      )
      val _ = server.http.postJson("/x", "{}") // force the spawn
      assertEquals(
        runner.lastArgs,
        Seq(
          "ollama",
          "launch",
          "opencode",
          "--model",
          "qwen3-coder",
          "--",
          "serve",
          "--port",
          "0",
          "--log-level",
          "WARN"
        )
      )

  test("a server that exits without binding surfaces its stderr"):
    supervised:
      val proc = new FakePipedCliProcess()
      proc.enqueueStderr(
        "Error: model \"gemma4\" not found; run 'ollama pull gemma4' first"
      )
      proc.closeStderr()
      proc.closeStdout() // EOF with no "listening on" line
      val server = new OpencodeServer(
        new RecordingRunner(proc),
        os.temp.dir(),
        httpFor = (_, _) => fail("client must not be built on a failed start")
      )
      val ex = intercept[OrcaFlowException](server.http)
      assert(
        ex.getMessage.contains("model \"gemma4\" not found"),
        ex.getMessage
      )

  test("shutdown destroys the process + closes the client; drains unblock"):
    // The drain forks block on a non-interruptible read for the server's life;
    // `shutdown`'s `destroyForcibly` is what EOFs them so the scope can join.
    // This process leaves stdout/stderr OPEN after the bind line (unlike the
    // others), so the drains are genuinely blocked until shutdown runs.
    supervised:
      val proc = new FakePipedCliProcess()
      proc.enqueueStdout("opencode server listening on http://127.0.0.1:4096")
      // deliberately NOT closing stdout/stderr — the drains stay blocked
      class TrackingHttp extends OpencodeHttp:
        @volatile var closed: Boolean = false
        def postJson(path: String, body: String): String = "ok"
        def events(): StreamSource = throw new UnsupportedOperationException
        override def close(): Unit = closed = true
      val client = new TrackingHttp
      val server =
        new OpencodeServer(
          new RecordingRunner(proc),
          os.temp.dir(),
          httpFor = (_, _) => client
        )

      val _ = server.http // force start: spawns, reads bind line, forks drains
      assert(proc.isAlive)
      server.shutdown()
      assert(!proc.isAlive, "shutdown must destroy the serve process")
      assert(client.closed, "shutdown must close the http client")
      server.shutdown() // idempotent: no exception, no double effect
    // The scope then joins the drain forks. (The fake's queue read is
    // interruptible, unlike a real native readLine, so this can't reproduce the
    // production hang — the destroy/close assertions above are the real teeth;
    // OpencodeServerTest's value is shutdown's effects + idempotency.)

  test("shutdown is a no-op when the server was never started"):
    supervised:
      val proc = new FakePipedCliProcess()
      val runner = new RecordingRunner(proc)
      val server =
        new OpencodeServer(
          runner,
          os.temp.dir(),
          httpFor = (_, _) => fail("unused")
        )
      server.shutdown() // never forced `http`
      assertEquals(runner.spawns.get(), 0)
