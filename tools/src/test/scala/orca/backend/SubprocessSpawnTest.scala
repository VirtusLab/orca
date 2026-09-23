package orca.backend

import orca.OrcaFlowException
import orca.events.OrcaListener
import orca.sweep.SweepFixtures

import ox.{supervised, timeout}

import scala.concurrent.duration.*

class SubprocessSpawnTest extends munit.FunSuite with SweepFixtures:

  onLinux("sweeps the spawned process when the scope ends, not before"):
    val pidFile = os.temp.dir(prefix = "orca-spawn-") / "detached.pid"
    val listener = new RecordingListener
    try
      supervised:
        val process =
          SubprocessSpawn.open("test", listener)(
            spawn(detachedWorker(pidFile))
          )(
            identity
          )
        val _ = awaitPid(pidFile)
        val _ = process.waitForExit()
        assertEquals(listener.steps, Nil)
      // The pid proves the report is about the spawned process's cookie.
      val detachedPid = awaitPid(pidFile)
      assertEquals(listener.steps.size, 1)
      assert(listener.steps.head.contains(detachedPid.toString), listener.steps)
    finally killPid(pidFile)

  test("a failed build kills the spawned process even when it ignores SIGINT"):
    val process = spawn("trap '' INT; echo ready; sleep 60")
    supervised:
      val _ = intercept[OrcaFlowException]:
        SubprocessSpawn.open("test", OrcaListener.noop)(process): p =>
          // Once `ready` is out, the trap is in place.
          val _ = p.stdoutLines.next()
          throw RuntimeException("build failed")
    val _ = timeout(5.seconds)(process.waitForExit())
