package orca

import munit.ScalaCheckSuite
import orca.progress.BranchName
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class OrcaArgsPropertyTest extends ScalaCheckSuite:

  private val promptGen: Gen[String] =
    for
      text <- arbitrary[String]
      prefix <- Gen.oneOf("", "-", "--", "- ", "--verbose ")
      suffix <- Gen.oneOf("", "\n- fix Y", "=x")
    yield prefix + text + suffix

  private val targetGen: Gen[RunTarget] =
    Gen.oneOf(
      RunTarget.NewBranch(Uncommitted.Stash),
      RunTarget.NewBranch(Uncommitted.Keep),
      RunTarget.CurrentBranch(Uncommitted.Stash),
      RunTarget.CurrentBranch(Uncommitted.Keep),
      RunTarget.Worktree
    )

  private val branchGen: Gen[BranchName] =
    Gen.identifier.map(id => BranchName.parse(s"feature/$id").toOption.get)

  // `parse` refuses a branch with a current-branch target, so none is drawn.
  private val argsGen: Gen[OrcaArgs] =
    for
      prompt <- promptGen
      verbose <- arbitrary[Boolean]
      target <- targetGen
      branch <-
        if target.skipBranch then Gen.const(None) else Gen.option(branchGen)
    yield OrcaArgs(prompt, verbose, target, branch)

  property("parse reads toArgv back as the same args"):
    forAll(argsGen): args =>
      assertEquals(OrcaArgs.parse(args.toArgv), Right(args))
