package orca.tools.opencode

import orca.backend.StreamSource
import orca.subprocess.{CliResult, CliRunner, PipedCliProcess}
import orca.sweep.SweepFixtures
import ox.supervised

class OpencodeServerSweepTest extends munit.FunSuite with SweepFixtures:

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

  onLinux("work the server detached is swept when the scope ends"):
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
        val server = OpencodeServer.start(
          new ScriptRunner(script),
          os.pwd,
          listener,
          httpFor = (_, _) =>
            new OpencodeHttp:
              def postJson(path: String, body: String): String = "ok"
              def events(): StreamSource =
                throw new UnsupportedOperationException
        )
        val _ = server.http
        val _ = awaitPid(pidFile)
      val detachedPid = awaitPid(pidFile)
      assertEquals(listener.steps.size, 1)
      assert(listener.steps.head.contains(detachedPid.toString), listener.steps)
    finally killPid(pidFile)
