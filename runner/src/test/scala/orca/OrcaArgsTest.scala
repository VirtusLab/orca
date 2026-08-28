package orca

import mainargs.Flag

class OrcaArgsTest extends munit.FunSuite:

  test("parses an empty argv into defaults (empty prompt, verbose off)"):
    assertEquals(OrcaArgs.parse(Nil), Right(OrcaArgs("", Flag())))

  test("a single positional argument becomes userPrompt"):
    val result = OrcaArgs
      .parse(Seq("implement feature X"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "implement feature X")
    assertEquals(result.verbose.value, false)

  test("--verbose flag sets verbose = true"):
    val result = OrcaArgs
      .parse(Seq("--verbose", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.verbose.value, true)

  test("--skip-branch flag sets skipBranch = true; absent defaults to false"):
    val result = OrcaArgs
      .parse(Seq("--skip-branch", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.skipBranch.value, true)
    assertEquals(
      OrcaArgs.parse(Seq("do the thing")).map(_.skipBranch.value),
      Right(false)
    )

  test("--worktree flag sets worktree = true"):
    val result = OrcaArgs
      .parse(Seq("--worktree", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.worktree.value, true)

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
