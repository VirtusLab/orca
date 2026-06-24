package orca.progress

import munit.FunSuite

class RecoveryCheckTest extends FunSuite:

  test("isSafeBranchRef accepts slug names and issue branches"):
    assert(RecoveryCheck.isSafeBranchRef("add-foo"))
    assert(RecoveryCheck.isSafeBranchRef("fix/issue-42"))
    assert(RecoveryCheck.isSafeBranchRef("flow-1a2b3c4d"))

  test("isSafeBranchRef rejects empty, leading-dash, traversal, and spaces"):
    assert(!RecoveryCheck.isSafeBranchRef(""))
    assert(!RecoveryCheck.isSafeBranchRef("-x"))
    assert(!RecoveryCheck.isSafeBranchRef("a/.."))
    assert(!RecoveryCheck.isSafeBranchRef("a b"))
    assert(!RecoveryCheck.isSafeBranchRef("Feat"))
    assert(!RecoveryCheck.isSafeBranchRef("a/"))

  test("validateHeader rejects the main/master floor regardless of the set"):
    val prompt = "do the thing"
    for protectedName <- List("main", "master", "MAIN", "Master") do
      val header = ProgressHeader(
        startingBranch = "main",
        branch = protectedName,
        promptHash = ProgressStore.hashPrompt(prompt)
      )
      assert(
        RecoveryCheck.validateHeader(header, prompt, Set.empty).isLeft,
        s"$protectedName must be rejected as a feature branch"
      )

  test("validateHeader rejects the repo's actual default branch"):
    val prompt = "do the thing"
    // A repo whose default is `trunk` (not main/master): a header naming it as
    // a feature branch must be refused when `trunk` is in the protected set.
    // `trunk` is lowercase + slug-valid, so it passes `isSafeBranchRef` and
    // reaches the protected-branch check — proving that code path fires (rather
    // than an incidental safe-ref rejection on a mixed-case name).
    val header = ProgressHeader(
      startingBranch = "trunk",
      branch = "trunk",
      promptHash = ProgressStore.hashPrompt(prompt)
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
    val ok = header.copy(branch = "feat/do-the-thing")
    assertEquals(
      RecoveryCheck.validateHeader(ok, prompt, Set("trunk")),
      Right(())
    )

  test("validateHeader allows a protected startingBranch"):
    val prompt = "do the thing"
    val header = ProgressHeader(
      startingBranch = "main",
      branch = "feat/do-the-thing",
      promptHash = ProgressStore.hashPrompt(prompt)
    )
    assertEquals(
      RecoveryCheck.validateHeader(header, prompt, Set.empty),
      Right(())
    )

  test("validateHeader rejects a prompt-hash mismatch"):
    val header = ProgressHeader(
      startingBranch = "main",
      branch = "feat/do-the-thing",
      promptHash = ProgressStore.hashPrompt("a different prompt")
    )
    assert(
      RecoveryCheck.validateHeader(header, "do the thing", Set.empty).isLeft
    )

  test("validateHeader rejects an unsafe startingBranch"):
    val prompt = "do the thing"
    val header = ProgressHeader(
      startingBranch = "-evil",
      branch = "feat/do-the-thing",
      promptHash = ProgressStore.hashPrompt(prompt)
    )
    assert(RecoveryCheck.validateHeader(header, prompt, Set.empty).isLeft)
