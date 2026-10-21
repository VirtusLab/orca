package orca.runner

import orca.lifecycle.WorktreeRun
import orca.effect.{FileSystem, Processes}
import orca.{OrcaDir, OrcaFlowException, RunKey}
import orca.testkit.GitRepo

class WorktreeRunTest extends munit.FunSuite:

  private val worktreeRun =
    WorktreeRun(Processes.ambient, FileSystem.ambient, FlowLock)

  test("resolving is refused while a live process holds the worktree lock"):
    val repo = GitRepo.seeded()
    val holder = LockHolder.acquire(LockHolder.Lock.Worktree(repo, "task A"))
    try
      val thrown = intercept[OrcaFlowException]:
        worktreeRun.resolve(repo, RunKey.of("task A"))
      assertEquals(
        thrown.getMessage,
        s"a flow is already running for this prompt (pid " +
          s"${holder.wrapped.pid()}) — wait for it to finish, or stop it"
      )
      assert(!os.exists(OrcaDir.worktreesPath(repo)), "nothing was created")
    finally LockHolder.kill(holder)
