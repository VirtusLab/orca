package orca

import orca.progress.BranchName

class RunTargetTest extends munit.FunSuite:

  test("toArgv renders each destination as the flags OrcaArgs parses back"):
    assertEquals(
      List(
        RunTarget.NewBranch(Uncommitted.Stash),
        RunTarget.NewBranch(Uncommitted.Keep),
        RunTarget.CurrentBranch(Uncommitted.Stash),
        RunTarget.CurrentBranch(Uncommitted.Keep),
        RunTarget.Worktree
      ).map(_.toArgv),
      List(
        Nil,
        Seq("--keep-changes"),
        Seq("--skip-branch"),
        Seq("--skip-branch", "--keep-changes"),
        Seq("--worktree")
      )
    )

  test("branchArgv renders the flag OrcaArgs parses back"):
    val branch = BranchName.parse("feat/x").toOption
    assertEquals(
      OrcaArgs.parse(RunTarget.branchArgv(branch) :+ "task").map(_.branch),
      Right(branch)
    )
