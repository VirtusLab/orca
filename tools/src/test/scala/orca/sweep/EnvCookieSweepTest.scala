package orca.sweep

import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{OsProcCliRunner, PipedCliProcess}

import java.util.concurrent.atomic.AtomicReference

class EnvCookieSweepTest extends munit.FunSuite:

  /** The sweep reads `/proc`; elsewhere it is inert and these tests have
    * nothing to assert.
    */
  private def onLinux(name: String)(body: => Any): Unit =
    if EnvCookieSweep.supported then test(name)(body)
    else test(name.ignore)(body)

  private def spawn(script: String): PipedCliProcess =
    OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", script),
      env = Map.empty,
      cwd = os.pwd,
      pipeStderr = false
    )

  private def cookieOf(process: PipedCliProcess): EnvCookie =
    process.envCookie.getOrElse(fail("spawnPiped must inject a cookie"))

  private def survivorPids(cookie: EnvCookie): List[Long] =
    EnvCookieSweep.sweep(cookie).map(_.pid)

  private def isAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)

  private def killPid(path: os.Path): Unit =
    readPid(path).foreach(pid =>
      ProcessHandle.of(pid).ifPresent(h => { val _ = h.destroyForcibly() })
    )

  private def readPid(path: os.Path): Option[Long] =
    Option.when(os.exists(path))(os.read(path).trim).flatMap(_.toLongOption)

  /** Polls for the pid the detached worker writes once it is up. The shell's
    * `>` creates the file before `echo` fills it, so this waits for a value
    * that parses, not for the file.
    */
  private def awaitPid(path: os.Path): Long =
    val deadline = System.currentTimeMillis + 5000
    var pid = readPid(path)
    while pid.isEmpty && System.currentTimeMillis < deadline do
      Thread.sleep(20)
      pid = readPid(path)
    pid.getOrElse(fail(s"the detached worker never wrote its pid to $path"))

  private class RecordingListener extends OrcaListener:
    private val recorded = new AtomicReference[List[String]](Nil)
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.Step(message) =>
        val _ = recorded.updateAndGet(message :: _)
      case _ => ()
    def steps: List[String] = recorded.get().reverse

  /** The case parent-link teardown cannot reach: `setsid` puts the worker in
    * its own session and the launching shell's immediate exit reparents it to
    * init, so it is in no descendant walk of anything orca spawned.
    */
  onLinux("reports a detached survivor and leaves it running"):
    val pidFile = os.temp.dir(prefix = "orca-sweep-") / "detached.pid"
    val process =
      spawn(s"""setsid bash -c 'echo $$$$ > "$pidFile"; sleep 60' & exit 0""")
    try
      val detachedPid = awaitPid(pidFile)
      // Wait so the launching shell is reaped: only the detached worker can
      // then be carrying the cookie.
      val _ = process.waitForExit()
      val cookie = cookieOf(process)
      assertEquals(survivorPids(cookie), List(detachedPid))

      val listener = RecordingListener()
      EnvCookieSweep.afterTurn(Some(cookie), listener)
      assertEquals(listener.steps.size, 1)
      assert(
        listener.steps.head.contains(detachedPid.toString),
        s"the report must name the detached pid: ${listener.steps}"
      )
      assert(isAlive(detachedPid), "report-only: the survivor must survive")
    finally killPid(pidFile)

  onLinux("matches only the process carrying the swept cookie"):
    val swept = spawn("echo $$; sleep 60")
    val decoy = spawn("echo $$; sleep 60")
    try
      val sweptPid = swept.stdoutLines.next().trim.toLong
      assertEquals(survivorPids(cookieOf(swept)), List(sweptPid))
    finally
      swept.destroyForciblyTree()
      decoy.destroyForciblyTree()
