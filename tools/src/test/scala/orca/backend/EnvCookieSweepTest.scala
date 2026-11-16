package orca.backend

import orca.subprocess.PipedCliProcess

class EnvCookieSweepTest extends munit.FunSuite with SweepFixtures:

  private def survivorPids(process: PipedCliProcess): List[Long] =
    process.survivors().map(_.pid)

  private def isAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)

  /** The case parent-link teardown cannot reach: `setsid` puts the worker in
    * its own session and the launching shell's immediate exit reparents it to
    * init, so it is in no descendant walk of anything orca spawned.
    */
  onLinux("reports a detached survivor and leaves it running"):
    val pidFile = os.temp.dir(prefix = "orca-sweep-") / "detached.pid"
    val process = spawn(detachedWorker(pidFile))
    try
      val detachedPid = awaitPid(pidFile)
      // Wait so the launching shell is reaped: only the detached worker can
      // then be carrying the cookie.
      val _ = process.waitForExit()
      assertEquals(survivorPids(process), List(detachedPid))

      val listener = new StepRecorder
      EnvCookieSweep.afterScope(process, listener)
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
      assertEquals(survivorPids(swept), List(sweptPid))
    finally
      swept.destroyForciblyTree()
      decoy.destroyForciblyTree()
