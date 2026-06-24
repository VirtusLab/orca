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

  test("validateHeader rejects a protected feature branch"):
    val prompt = "do the thing"
    for protectedName <- List("main", "master", "MAIN", "Master") do
      val header = ProgressHeader(
        startingBranch = "main",
        branch = protectedName,
        promptHash = ProgressStore.hashPrompt(prompt)
      )
      assert(
        RecoveryCheck.validateHeader(header, prompt).isLeft,
        s"$protectedName must be rejected as a feature branch"
      )

  test("validateHeader allows a protected startingBranch"):
    val prompt = "do the thing"
    val header = ProgressHeader(
      startingBranch = "main",
      branch = "feat/do-the-thing",
      promptHash = ProgressStore.hashPrompt(prompt)
    )
    assertEquals(RecoveryCheck.validateHeader(header, prompt), Right(()))

  test("validateHeader rejects a prompt-hash mismatch"):
    val header = ProgressHeader(
      startingBranch = "main",
      branch = "feat/do-the-thing",
      promptHash = ProgressStore.hashPrompt("a different prompt")
    )
    assert(RecoveryCheck.validateHeader(header, "do the thing").isLeft)

  test("validateHeader rejects an unsafe startingBranch"):
    val prompt = "do the thing"
    val header = ProgressHeader(
      startingBranch = "-evil",
      branch = "feat/do-the-thing",
      promptHash = ProgressStore.hashPrompt(prompt)
    )
    assert(RecoveryCheck.validateHeader(header, prompt).isLeft)
