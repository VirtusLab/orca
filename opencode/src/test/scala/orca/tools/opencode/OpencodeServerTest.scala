package orca.tools.opencode

import orca.OrcaFlowException
import orca.backend.StreamSource
import orca.events.OrcaListener
import orca.subprocess.{
  CliResult,
  CliRunner,
  FakePipedCliProcess,
  PipedCliProcess,
  SpawnStubCliRunner
}
import orca.sweep.SweepFixtures
import orca.testkit.TempDirs
import ox.{fork, supervised}

class OpencodeServerTest extends munit.FunSuite with SweepFixtures:

  test("parseBaseUrl extracts the URL from the listening line"):
    assertEquals(
      OpencodeServer.parseBaseUrl(
        "opencode server listening on http://127.0.0.1:4096"
      ),
      Some("http://127.0.0.1:4096")
    )
    assertEquals(OpencodeServer.parseBaseUrl("starting up"), None)

  private def listeningProcess: FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout("opencode server listening on http://127.0.0.1:4096")
    p.closeStdout()
    p.closeStderr()
    p

  private val stubHttp: OpencodeHttp = new OpencodeHttp:
    def postJson(path: String, body: String): String = "ok"
    def events(): StreamSource = throw new UnsupportedOperationException

  test("the first call spawns serve and builds a client for its URL"):
    val runner = new SpawnStubCliRunner(List(listeningProcess))
    var builtFor: Option[String] = None
    supervised:
      val server = OpencodeServer(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (url, _) =>
          builtFor = Some(url)
          stubHttp
      )
      assertEquals(server.http(), stubHttp)
    val spawned = runner.spawnCalls.head
    assertEquals(
      spawned.args,
      List("opencode", "serve", "--port", "0", "--log-level", "WARN")
    )
    assert(spawned.env.contains("OPENCODE_SERVER_PASSWORD"))
    assertEquals(builtFor, Some("http://127.0.0.1:4096"))

  test("a second call reuses the running server"):
    val runner = new SpawnStubCliRunner(List(listeningProcess))
    supervised:
      val server = OpencodeServer(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => stubHttp
      )
      val _ = server.http()
      val _ = server.http()
    assertEquals(runner.spawnCalls.size, 1)

  test("concurrent first calls spawn serve once"):
    val runner = new SpawnStubCliRunner(List(listeningProcess))
    supervised:
      val server = OpencodeServer(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => stubHttp
      )
      val calls = List.fill(2)(fork(server.http()))
      calls.foreach(_.join())
    assertEquals(runner.spawnCalls.size, 1)

  test("a custom launcher wraps the serve argv at spawn"):
    val runner = new SpawnStubCliRunner(List(listeningProcess))
    supervised:
      val server = OpencodeServer(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        launcher = OpencodeLauncher.ollama("qwen3-coder"),
        httpFor = (_, _) => stubHttp
      )
      val _ = server.http()
    assertEquals(
      runner.calls,
      List(
        List(
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
    )

  test("a server that exits without binding surfaces its stderr"):
    val proc = new FakePipedCliProcess()
    proc.enqueueStderr(
      "Error: model \"gemma4\" not found; run 'ollama pull gemma4' first"
    )
    proc.closeStderr()
    proc.closeStdout() // EOF with no "listening on" line
    supervised:
      val server = OpencodeServer(
        new SpawnStubCliRunner(List(proc)),
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => fail("client must not be built on a failed start")
      )
      val ex = intercept[OrcaFlowException](server.http())
      assert(
        ex.getMessage.contains("model \"gemma4\" not found"),
        ex.getMessage
      )

  test("a failed start is retried on the next call"):
    val failing = new FakePipedCliProcess()
    failing.closeStdout() // EOF with no "listening on" line
    failing.closeStderr()
    val runner = new SpawnStubCliRunner(List(failing, listeningProcess))
    supervised:
      val server = OpencodeServer(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => stubHttp
      )
      val _ = intercept[OrcaFlowException](server.http())
      assertEquals(server.http(), stubHttp)
    assertEquals(runner.spawnCalls.size, 2)

  test("scope end destroys the process and closes the client"):
    // Stdout/stderr stay open after the bind line, so the drains are blocked
    // until the process is destroyed.
    val proc = new FakePipedCliProcess()
    proc.enqueueStdout("opencode server listening on http://127.0.0.1:4096")
    val client = new TrackingHttp
    supervised:
      val server = OpencodeServer(
        new SpawnStubCliRunner(List(proc)),
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => client
      )
      val _ = server.http()
      assert(proc.isAlive)
    assert(!proc.isAlive, "scope end must destroy the serve process")
    assert(client.closed, "scope end must close the http client")

  test("nothing is spawned when the server is never used"):
    val runner = new SpawnStubCliRunner(Nil)
    supervised:
      val _ = OpencodeServer(
        runner,
        TempDirs.dir(),
        OrcaListener.noop,
        httpFor = (_, _) => fail("unused")
      )
    assertEquals(runner.spawnCalls, Nil)

  onLinux("work detached by the server is reported when the scope ends"):
    val pidFile = os.temp.dir(prefix = "orca-opencode-") / "detached.pid"
    // The subshell exits at once, so the worker leaves the server's process
    // tree; its streams are redirected so it doesn't hold the drained pipes.
    val script =
      s"""echo "opencode server listening on http://127.0.0.1:1"
         |( setsid bash -c 'echo $$$$ > "$pidFile"; sleep 60' >/dev/null 2>&1 </dev/null & )
         |sleep 60""".stripMargin
    val listener = new RecordingListener
    try
      supervised:
        val server = OpencodeServer(
          new ScriptRunner(script),
          os.pwd,
          listener,
          httpFor = (_, _) => stubHttp
        )
        val _ = server.http()
        val _ = awaitPid(pidFile)
      val detachedPid = awaitPid(pidFile)
      assertEquals(listener.steps.size, 1)
      assert(listener.steps.head.contains(detachedPid.toString), listener.steps)
    finally killPid(pidFile)

  private class TrackingHttp extends OpencodeHttp:
    @volatile var closed: Boolean = false
    def postJson(path: String, body: String): String = "ok"
    def events(): StreamSource = throw new UnsupportedOperationException
    override def close(): Unit = closed = true

  /** Runs `script` in place of `opencode serve`. */
  private class ScriptRunner(script: String) extends CliRunner:
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
    ): PipedCliProcess = spawn(script)
