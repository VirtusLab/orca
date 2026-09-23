package orca.tools.opencode

import orca.OrcaFlowException
import orca.events.OrcaListener
import orca.backend.StreamSource
import orca.subprocess.{
  CliResult,
  CliRunner,
  FakePipedCliProcess,
  PipedCliProcess
}
import ox.supervised

import java.util.concurrent.atomic.AtomicInteger
import orca.testkit.TempDirs

class OpencodeServerTest extends munit.FunSuite:

  test("parseBaseUrl extracts the URL from the listening line"):
    assertEquals(
      OpencodeServer.parseBaseUrl(
        "opencode server listening on http://127.0.0.1:4096"
      ),
      Some("http://127.0.0.1:4096")
    )
    assertEquals(OpencodeServer.parseBaseUrl("starting up"), None)

  /** Hands out `processes` in order, one per spawn. */
  private class RecordingRunner(processes: PipedCliProcess*) extends CliRunner:
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
      lastArgs = args
      lastEnv = env
      processes(spawns.getAndIncrement())

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
      val server = OpencodeServer.start(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
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
      val server = OpencodeServer.start(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
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
      val server = OpencodeServer.start(
        new RecordingRunner(proc),
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => fail("client must not be built on a failed start")
      )
      val ex = intercept[OrcaFlowException](server.http)
      assert(
        ex.getMessage.contains("model \"gemma4\" not found"),
        ex.getMessage
      )

  test("a failed start is retried on the next call"):
    supervised:
      val failing = new FakePipedCliProcess()
      failing.closeStdout() // EOF with no "listening on" line
      failing.closeStderr()
      val runner = new RecordingRunner(failing, listeningProcess)
      val server = OpencodeServer.start(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => stubHttp
      )
      val _ = intercept[OrcaFlowException](server.http)
      assertEquals(server.http, stubHttp)
      assertEquals(runner.spawns.get(), 2)

  test("scope end destroys the process and closes the client"):
    // Stdout/stderr stay open after the bind line, so the drains are blocked
    // until the process is destroyed.
    val proc = new FakePipedCliProcess()
    proc.enqueueStdout("opencode server listening on http://127.0.0.1:4096")
    val client = new TrackingHttp
    supervised:
      val server = OpencodeServer.start(
        new RecordingRunner(proc),
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => client
      )
      val _ = server.http
      assert(proc.isAlive)
    assert(!proc.isAlive, "scope end must destroy the serve process")
    assert(client.closed, "scope end must close the http client")

  test("nothing is spawned when the server is never used"):
    val runner = new RecordingRunner(new FakePipedCliProcess())
    supervised:
      val _ = OpencodeServer.start(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => fail("unused")
      )
    assertEquals(runner.spawns.get(), 0)

  private val stubHttp: OpencodeHttp = new OpencodeHttp:
    def postJson(path: String, body: String): String = "ok"
    def events(): StreamSource = throw new UnsupportedOperationException

  private class TrackingHttp extends OpencodeHttp:
    @volatile var closed: Boolean = false
    def postJson(path: String, body: String): String = "ok"
    def events(): StreamSource = throw new UnsupportedOperationException
    override def close(): Unit = closed = true
