package orca.subprocess

import orca.sweep.EnvCookie

class OsProcCliRunnerTest extends munit.FunSuite:

  private def alive(pid: Long): Boolean =
    val h = ProcessHandle.of(pid)
    h.isPresent && h.get.isAlive

  private def awaitDead(pid: Long): Boolean =
    val deadline = System.currentTimeMillis + 3000
    while alive(pid) && System.currentTimeMillis < deadline do Thread.sleep(20)
    !alive(pid)

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

  /** The spawn's cookie must reach the child ON TOP OF the environment it would
    * have inherited anyway — os-lib augments rather than replaces, and the
    * sweep would be useless if adding the cookie stripped the CLI's own
    * configuration.
    */
  test("spawnPiped adds its cookie to the inherited environment"):
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
        s"${cookie.value}|${sys.env("HOME")}|from-caller"
      )
    finally proc.destroyForciblyTree()
