package orca.backend

import orca.OrcaFlowException
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{OsProcCliRunner, PipedCliProcess}
import orca.sweep.EnvCookieSweep

import ox.{supervised, timeout}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

class SubprocessSpawnTest extends munit.FunSuite:

  private def spawn(script: String): PipedCliProcess =
    OsProcCliRunner.spawnPiped(
      Seq("bash", "-c", script),
      env = Map.empty,
      cwd = os.pwd,
      pipeStderr = false
    )

  private class RecordingListener extends OrcaListener:
    private val recorded = new AtomicReference[List[String]](Nil)
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.Step(message) =>
        val _ = recorded.updateAndGet(message :: _)
      case _ => ()
    def steps: List[String] = recorded.get().reverse

  private def readPid(path: os.Path): Option[Long] =
    Option.when(os.exists(path))(os.read(path).trim).flatMap(_.toLongOption)

  private def awaitPid(path: os.Path): Long =
    val deadline = System.currentTimeMillis + 5000
    var pid = readPid(path)
    while pid.isEmpty && System.currentTimeMillis < deadline do
      Thread.sleep(20)
      pid = readPid(path)
    pid.getOrElse(fail(s"the detached worker never wrote its pid to $path"))

  /** The sweep reads `/proc`; elsewhere it is inert. */
  private def onLinux(name: String)(body: => Any): Unit =
    if EnvCookieSweep.supported then test(name)(body)
    else test(name.ignore)(body)

  onLinux("sweeps the spawned process when the scope ends"):
    val pidFile = os.temp.dir(prefix = "orca-spawn-") / "detached.pid"
    val listener = RecordingListener()
    try
      supervised:
        val process = SubprocessSpawn.open("test", listener)(
          spawn(
            s"""setsid bash -c 'echo $$$$ > "$pidFile"; sleep 60' & exit 0"""
          )
        )(identity)
        val _ = awaitPid(pidFile)
        val _ = process.waitForExit()
        assertEquals(listener.steps, Nil)
      val detachedPid = awaitPid(pidFile)
      assertEquals(listener.steps.size, 1)
      assert(
        listener.steps.head.contains(detachedPid.toString),
        listener.steps
      )
    finally
      readPid(pidFile).foreach(pid =>
        ProcessHandle.of(pid).ifPresent(h => { val _ = h.destroyForcibly() })
      )

  test("a failed build kills the spawned process even when it ignores SIGINT"):
    val spawned = new AtomicReference[Option[PipedCliProcess]](None)
    supervised:
      val _ = intercept[OrcaFlowException]:
        SubprocessSpawn.open("test", OrcaListener.noop)(
          spawn("trap '' INT; sleep 60")
        ): process =>
          spawned.set(Some(process))
          throw RuntimeException("build failed")
    val process = spawned.get().getOrElse(fail("nothing was spawned"))
    val _ = timeout(5.seconds)(process.waitForExit())
