package orca.progress

import munit.FunSuite
import orca.gitref.CommitHash
import orca.testkit.branchName

class RecoveryCheckTest extends FunSuite:

  private val testCommit: CommitHash = CommitHash.from("0" * 40).get

  test("validateHeader rejects the main/master floor regardless of the set"):
    val prompt = "do the thing"
    for protectedName <- List("main", "master", "MAIN", "Master") do
      val header = ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName(protectedName),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = testCommit
      )
      assert(
        RecoveryCheck.validateHeader(header, prompt, Set.empty).isLeft,
        s"$protectedName must be rejected as a feature branch"
      )

  test("validateHeader rejects the repo's actual default branch"):
    val prompt = "do the thing"
    // A repo whose default is `trunk` (not main/master): a header naming it as
    // a feature branch must be refused when `trunk` is in the protected set.
    val header = ProgressHeader(
      startingBranch = Some(branchName("trunk")),
      branch = branchName("trunk"),
      branchMode = BranchMode.Created,
      userPrompt = prompt,
      flow = None,
      startingCommit = testCommit
    )
    val rejected = RecoveryCheck.validateHeader(header, prompt, Set("trunk"))
    assert(
      rejected.left.exists(_.contains("protected")),
      s"the default branch must be rejected as PROTECTED, got: $rejected"
    )
    // Case-insensitive: a mixed-case entry in the protected set still matches.
    assert(
      RecoveryCheck
        .validateHeader(header, prompt, Set("Trunk"))
        .left
        .exists(_.contains("protected")),
      "protected-branch match must be case-insensitive"
    )
    // ...but a normal feature branch still passes with the same set.
    val ok = header.copy(branch = branchName("feat/do-the-thing"))
    assertEquals(
      RecoveryCheck.validateHeader(ok, prompt, Set("trunk")).map(_.value),
      Right("feat/do-the-thing")
    )

  test("validateHeader allows a protected startingBranch"):
    val prompt = "do the thing"
    val header = ProgressHeader(
      startingBranch = Some(branchName("main")),
      branch = branchName("feat/do-the-thing"),
      branchMode = BranchMode.Created,
      userPrompt = prompt,
      flow = None,
      startingCommit = testCommit
    )
    assertEquals(
      RecoveryCheck.validateHeader(header, prompt, Set.empty).map(_.value),
      Right("feat/do-the-thing")
    )

  test("validateHeader rejects a header written for a different prompt"):
    val header = ProgressHeader(
      startingBranch = Some(branchName("main")),
      branch = branchName("feat/do-the-thing"),
      branchMode = BranchMode.Created,
      userPrompt = "a different prompt",
      flow = None,
      startingCommit = testCommit
    )
    assert(
      RecoveryCheck.validateHeader(header, "do the thing", Set.empty).isLeft
    )
