package orca.subprocess

import orca.sweep.EnvCookie
import orca.testkit.ProcessProbe.{alive, awaitDead, awaitTrue}
import orca.testkit.TempDirs
import ox.discard

class OsProcCliRunnerTest extends munit.FunSuite:

  private def killPid(pid: Long): Unit =
    if pid > 0 then ProcessHandle.of(pid).ifPresent(_.destroyForcibly().discard)

  /** Spawns a shell that backgrounds a `sleep` and stays alive as its parent,
    * yields the backgrounded PID, and tree-kills whatever is left afterwards so
    * a failing assertion can't leak either process. `$!` is the backgrounded
    * job's PID; `wait` is what keeps the shell running.
    */
  private def withBackgroundedChild(
      body: (PipedCliProcess, Long) => Unit
  ): Unit =
    val proc = OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", "sleep 30 & echo $!; wait"),
      env = Map.empty,
      cwd = os.pwd,
      pipeStderr = true
    )
    var childPid = 0L
    try
      childPid = proc.stdoutLines.next().trim.toLong
      body(proc, childPid)
    finally
      proc.destroyForciblyTree()
      killPid(childPid)

  /** A launch wrapper forks a worker that inherits the stdout/stderr pipes, so
    * killing only the wrapper PID would orphan it and leave a drain reader
    * blocked on a never-EOF'd pipe. `destroyForciblyTree` must reap the
    * descendant too, unlike the PID-only `destroyForcibly`.
    */
  test("destroyForciblyTree reaps a live descendant"):
    withBackgroundedChild: (proc, childPid) =>
      assert(alive(childPid), "the forked descendant should be running")

      proc.destroyForciblyTree()

      assert(
        awaitDead(childPid),
        "tree kill must terminate the forked descendant"
      )

  /** The case the signal-time snapshot exists for, made deterministic by
    * letting the root exit first: a `&`-backgrounded job in a non-interactive
    * shell inherits SIGINT as ignored, so it survives the signal that kills its
    * parent and is reparented to init — off the root's `descendants()` walk
    * entirely by the time the forcible step runs.
    */
  test("destroyForciblyTree reaps a descendant the SIGINT orphaned"):
    withBackgroundedChild: (proc, childPid) =>
      proc.sendSigInt()
      assert(awaitTrue(!proc.isAlive), "the SIGINT should end the root shell")
      assert(alive(childPid), "the orphan should have survived the SIGINT")

      proc.destroyForciblyTree()

      assert(
        awaitDead(childPid),
        "the signal-time snapshot must still reach an orphaned descendant"
      )

  /** A remembered handle is a branch of the tree, not a leaf: the orphan can
    * spawn children of its own after the snapshot was taken, and with the root
    * already dead nothing else can reach them.
    *
    * Sequenced through the filesystem, not stdout: once the root exits, the JDK
    * reaper drains and closes our read end of the pipe
    * (`ProcessPipeInputStream.processExited`), so nothing the orphan writes
    * afterwards is readable. The orphan spawns its child only when the test
    * creates the trigger, which it does after the snapshot has been taken.
    */
  test("destroyForciblyTree re-expands a remembered orphan's own children"):
    val dir = TempDirs.dir()
    val trigger = dir / "spawn-now"
    val pidFile = dir / "grandchild.pid"
    // Single-quoted so the inner shell expands `$TRIGGER`/`$PIDFILE` and its own
    // `$!`; the outer `$!` is the inner shell's PID.
    val script =
      """bash -c 'while [ ! -e "$TRIGGER" ]; do sleep 0.02; done""" +
        """; sleep 30 & echo $! > "$PIDFILE"; wait' & echo $!; wait"""
    val proc = OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", script),
      env = Map("TRIGGER" -> trigger.toString, "PIDFILE" -> pidFile.toString),
      cwd = os.pwd,
      pipeStderr = true
    )
    var orphanPid = 0L
    var grandchildPid = 0L
    try
      orphanPid = proc.stdoutLines.next().trim.toLong
      proc.sendSigInt()
      assert(awaitTrue(!proc.isAlive), "the SIGINT should end the root shell")

      os.write(trigger, "")
      assert(
        awaitTrue(os.exists(pidFile) && os.read(pidFile).trim.nonEmpty),
        "the orphan should have spawned its child on the trigger"
      )
      grandchildPid = os.read(pidFile).trim.toLong
      assert(alive(grandchildPid), "the late grandchild should be running")

      proc.destroyForciblyTree()

      assert(
        awaitDead(grandchildPid),
        "a remembered orphan's later children must be reaped too"
      )
    finally
      proc.destroyForciblyTree()
      killPid(grandchildPid)
      killPid(orphanPid)

  /** Teardown runs unconditionally in a `finally`, so on the happy path it hits
    * a process that already exited — and `descendants()` on a dead handle is an
    * empty stream, not a failure.
    */
  test("destroyForciblyTree on an already-exited process is a no-op"):
    val proc = OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", "exit 0"),
      env = Map.empty,
      cwd = os.pwd,
      pipeStderr = true
    )
    assertEquals(proc.waitForExit(), 0)

    proc.destroyForciblyTree()
    proc.destroyForciblyTree()

    assertEquals(
      proc.tryExitCode,
      Some(0),
      "the recorded exit code must survive a post-exit tree kill"
    )

  /** Injecting the cookie must not strip the environment the CLI would
    * otherwise inherit, nor the caller's own additions.
    */
  test("spawnPiped adds its cookie to the inherited environment"):
    val home = sys.env.getOrElse("HOME", "")
    assume(home.nonEmpty, "needs an inherited variable to check against")
    val proc = OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", s"""echo "$$${EnvCookie.VarName}|$$HOME|$$EXTRA""""),
      env = Map("EXTRA" -> "from-caller"),
      cwd = os.pwd,
      pipeStderr = false
    )
    try
      val cookie = proc.envCookie.getOrElse(fail("no cookie was injected"))
      assertEquals(
        proc.stdoutLines.next(),
        s"${cookie.value}|$home|from-caller"
      )
    finally proc.destroyForciblyTree()
