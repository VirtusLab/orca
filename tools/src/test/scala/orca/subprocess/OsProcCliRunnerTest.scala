package orca.subprocess

import orca.testkit.ProcessProbe.{alive, awaitDead}

class OsProcCliRunnerTest extends munit.FunSuite:

  /** A launch wrapper forks a worker that inherits the stdout/stderr pipes, so
    * killing only the wrapper PID would orphan it and leave a drain reader
    * blocked on a never-EOF'd pipe. `destroyForciblyTree` must reap the
    * descendant too, unlike the PID-only `destroyForcibly`.
    */
  test("destroyForciblyTree reaps a forked descendant"):
    // `$!` is the backgrounded sleep's PID; `wait` keeps bash alive as its
    // parent so it is reachable via `descendants()` at kill time.
    val proc = OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", "sleep 30 & echo $!; wait"),
      env = Map.empty,
      cwd = os.pwd,
      pipeStderr = true
    )
    val childPid = proc.stdoutLines.next().trim.toLong
    assert(alive(childPid), "the forked descendant should be running")

    proc.destroyForciblyTree()

    assert(
      awaitDead(childPid),
      "tree kill must terminate the forked descendant"
    )

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
