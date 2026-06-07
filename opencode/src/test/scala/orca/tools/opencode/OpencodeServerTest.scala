package orca.tools.opencode

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
        (url, pwd) =>
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
