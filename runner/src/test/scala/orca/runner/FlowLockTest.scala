package orca.runner

import orca.OrcaDir
import orca.runner.LockHolder.{Lock, Outcome}
import orca.testkit.TempDirs

class FlowLockTest extends munit.FunSuite:

  test("a lock whose holder was killed is free"):
    val workDir = TempDirs.dir()
    val holder = LockHolder.acquire(Lock.Workdir(workDir))
    try
      LockHolder.kill(holder)
      assertEquals(FlowLock.workdirLocked(workDir)("ran"), "ran")
    finally LockHolder.kill(holder)

  test(
    "of processes racing for a stale lock file, exactly one acquires it"
  ):
    val workDir = TempDirs.dir()
    // A lock file left by a holder that is gone.
    os.write(
      OrcaDir.flowLockPath(workDir),
      Long.MaxValue.toString,
      createFolders = true
    )
    val holders = List.fill(4)(LockHolder.spawn(Lock.Workdir(workDir)))
    try
      holders.foreach(LockHolder.go)
      val outcomes = holders.map(LockHolder.outcome)
      assertEquals(
        outcomes.count(_ == Outcome.Acquired),
        1,
        outcomes.mkString("\n")
      )
    finally holders.foreach(LockHolder.kill)
