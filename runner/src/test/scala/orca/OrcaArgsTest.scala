package orca

class OrcaArgsTest extends munit.FunSuite:

  test("parses an empty argv into defaults (empty prompt, verbose off)"):
    assertEquals(
      OrcaArgs.parse(Nil),
      Right(
        OrcaArgs("", verbose = false, RunTarget.NewBranch(Uncommitted.Stash))
      )
    )

  test("a single positional argument becomes userPrompt"):
    val result = OrcaArgs
      .parse(Seq("implement feature X"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "implement feature X")
    assertEquals(result.verbose, false)

  test("--verbose flag sets verbose = true"):
    val result = OrcaArgs
      .parse(Seq("--verbose", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.verbose, true)

  test("--skip-branch targets the current branch; absent, a new one"):
    val result = OrcaArgs
      .parse(Seq("--skip-branch", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.target, RunTarget.CurrentBranch(Uncommitted.Stash))
    assertEquals(
      OrcaArgs.parse(Seq("do the thing")).map(_.target),
      Right(RunTarget.NewBranch(Uncommitted.Stash))
    )

  test("--keep-changes keeps uncommitted files on the chosen branch"):
    assertEquals(
      OrcaArgs.parse(Seq("--keep-changes", "do the thing")).map(_.target),
      Right(RunTarget.NewBranch(Uncommitted.Keep))
    )

  test("--skip-branch with --keep-changes: current branch, files kept"):
    assertEquals(
      OrcaArgs
        .parse(Seq("--skip-branch", "--keep-changes", "do the thing"))
        .map(_.target),
      Right(RunTarget.CurrentBranch(Uncommitted.Keep))
    )

  test("--worktree targets a worktree"):
    val result = OrcaArgs
      .parse(Seq("--worktree", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.target, RunTarget.Worktree)

  test("--worktree with --skip-branch is refused, naming both flags"):
    assertRefused(Seq("--worktree", "--skip-branch", "x"), "--skip-branch")

  test("--worktree with --keep-changes is refused, naming both flags"):
    assertRefused(Seq("--worktree", "--keep-changes", "x"), "--keep-changes")

  private def assertRefused(argv: Seq[String], otherFlag: String): Unit =
    OrcaArgs.parse(argv) match
      case Left(msg) =>
        assert(msg.contains("--worktree"), msg)
        assert(msg.contains(otherFlag), msg)
      case Right(r) => fail(s"expected a refusal, got $r")

  test("unknown flags yield a Left with an error message"):
    OrcaArgs.parse(Seq("--nonexistent")) match
      case Left(msg) => assert(msg.nonEmpty)
      case Right(r)  => fail(s"expected parse failure, got $r")
