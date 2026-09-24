package orca.sweep

import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{OsProcCliRunner, PipedCliProcess}

import java.util.concurrent.atomic.AtomicReference

/** Real processes, detached workers and a report recorder for sweep tests. */
trait SweepFixtures:
  self: munit.FunSuite =>

  /** The sweep reads `/proc`; elsewhere it is inert and these tests have
    * nothing to assert.
    */
  protected def onLinux(
      name: String
  )(body: => Any)(using munit.Location): Unit =
    if EnvCookieSweep.supported then test(name)(body)
    else test(name.ignore)(body)

  protected def spawn(script: String): PipedCliProcess =
    OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", script),
      env = Map.empty,
      cwd = os.pwd
    )

  /** A script that starts a worker `setsid` detaches from the spawned process's
    * tree, writing its pid to `pidFile`, and exits.
    */
  protected def detachedWorker(pidFile: os.Path): String =
    s"""setsid bash -c 'echo $$$$ > "$pidFile"; sleep 60' & exit 0"""

  protected def killPid(path: os.Path): Unit =
    readPid(path).foreach(pid =>
      ProcessHandle.of(pid).ifPresent(h => { val _ = h.destroyForcibly() })
    )

  private def readPid(path: os.Path): Option[Long] =
    Option.when(os.exists(path))(os.read(path).trim).flatMap(_.toLongOption)

  /** Polls for the pid the detached worker writes once it is up. The shell's
    * `>` creates the file before `echo` fills it, so this waits for a value
    * that parses, not for the file.
    */
  protected def awaitPid(path: os.Path): Long =
    val deadline = System.currentTimeMillis + 5000
    var pid = readPid(path)
    while pid.isEmpty && System.currentTimeMillis < deadline do
      Thread.sleep(20)
      pid = readPid(path)
    pid.getOrElse(fail(s"the detached worker never wrote its pid to $path"))

  protected class RecordingListener extends OrcaListener:
    private val recorded = new AtomicReference[List[String]](Nil)
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.Step(message) =>
        val _ = recorded.updateAndGet(message :: _)
      case _ => ()
    def steps: List[String] = recorded.get().reverse
