package orca

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
