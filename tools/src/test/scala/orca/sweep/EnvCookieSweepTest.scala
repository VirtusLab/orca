package orca.sweep

import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{OsProcCliRunner, PipedCliProcess}

import java.util.concurrent.atomic.AtomicReference

class EnvCookieSweepTest extends munit.FunSuite:

  /** The sweep reads `/proc`; elsewhere it reports itself unsupported, and
    * these tests have nothing to assert.
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
    EnvCookieSweep.sweep(cookie) match
      case SweepOutcome.Scanned(survivors) => survivors.map(_.pid)
      case SweepOutcome.Unsupported        => fail("expected a scan")

  private def kill(pid: Long): Unit =
    ProcessHandle.of(pid).ifPresent(h => { val _ = h.destroyForcibly() })

  /** Polls for the pid the detached worker writes once it is up, so the test
    * waits on the condition rather than on a duration.
    */
  private def awaitPidFile(path: os.Path): Long =
    val deadline = System.currentTimeMillis + 5000
    while !os.exists(path) && System.currentTimeMillis < deadline do
      Thread.sleep(20)
    Option
      .when(os.exists(path))(os.read(path).trim)
      .flatMap(_.toLongOption)
      .getOrElse(fail(s"the detached worker never wrote its pid to $path"))

  private class RecordingListener extends OrcaListener:
    private val recorded = new AtomicReference[List[String]](Nil)
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.Step(message) =>
        val _ = recorded.updateAndGet(message :: _)
      case _ => ()
    def steps: List[String] = recorded.get().reverse

  /** The case parent-link teardown cannot reach: `setsid` puts the worker in
    * its own session and the launching shell's immediate exit reparents it to
    * init, so it is in no descendant walk of anything orca spawned. Only the
    * inherited environment still ties it to the turn.
    */
  onLinux("reports work the agent detached from orca's process tree"):
    val pidFile = os.temp.dir(prefix = "orca-sweep-") / "detached.pid"
    val process =
      spawn(s"""setsid bash -c 'echo $$$$ > "$pidFile"; sleep 60' & exit 0""")
    val detachedPid = awaitPidFile(pidFile)
    try
      // The launching shell is gone, so only the detached worker can be found.
      val _ = process.waitForExit()
      val listener = RecordingListener()
      EnvCookieSweep.afterTurn(Some(cookieOf(process)), listener)
      assertEquals(
        listener.steps.count(_.contains(detachedPid.toString)),
        1,
        s"the report must name the detached pid: ${listener.steps}"
      )
    finally kill(detachedPid)

  onLinux("matches only the process carrying the swept cookie"):
    val swept = spawn("echo $$; sleep 60")
    val other = spawn("echo $$; sleep 60")
    try
      val sweptPid = swept.stdoutLines.next().trim.toLong
      val otherPid = other.stdoutLines.next().trim.toLong
      assertEquals(survivorPids(cookieOf(swept)), List(sweptPid))
      assertEquals(survivorPids(cookieOf(other)), List(otherPid))
    finally
      swept.destroyForciblyTree()
      other.destroyForciblyTree()

  onLinux("does not report a process that has exited"):
    val process = spawn("exit 0")
    val _ = process.waitForExit()
    assertEquals(survivorPids(cookieOf(process)), Nil)
