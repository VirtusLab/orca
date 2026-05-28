package orca.plan

class BugTriageTest extends munit.FunSuite:

  // Wire format with all-empty defaults — each test sets only the fields its
  // branch needs, so a missing copy() argument is itself a regression check.
  private val empty = BugTriage(
    isBug = false,
    notBugExplanation = "",
    canTest = false,
    reproductionSteps = "",
    failingTestPath = None,
    branchName = "",
    summary = ""
  )

  test("toTriage(NotABug) requires a non-empty explanation"):
    assertEquals(
      empty.copy(notBugExplanation = "intended behavior").toTriage,
      Right(Triage.NotABug("intended behavior"))
    )
    assert(empty.toTriage.isLeft)
    assert(empty.copy(notBugExplanation = "   ").toTriage.isLeft)

  test("toTriage(Untestable) requires summary + reproductionSteps"):
    val ok = empty.copy(
      isBug = true,
      canTest = false,
      summary = "race in shutdown",
      reproductionSteps = "1. run 2. ctrl-c"
    )
    assertEquals(
      ok.toTriage,
      Right(Triage.Untestable("race in shutdown", "1. run 2. ctrl-c"))
    )
    assert(ok.copy(summary = "").toTriage.isLeft)
    assert(ok.copy(reproductionSteps = "").toTriage.isLeft)

  test("toTriage(Testable) requires summary + branchName + failingTestPath"):
    val ok = empty.copy(
      isBug = true,
      canTest = true,
      summary = "add overflow",
      branchName = "fix-add-overflow",
      failingTestPath = Some("src/test/scala/example/AddOverflowTest.scala")
    )
    assertEquals(
      ok.toTriage,
      Right(
        Triage.Testable(
          "add overflow",
          "fix-add-overflow",
          "src/test/scala/example/AddOverflowTest.scala"
        )
      )
    )
    assert(ok.copy(failingTestPath = None).toTriage.isLeft)
    assert(ok.copy(failingTestPath = Some("  ")).toTriage.isLeft)
    assert(ok.copy(branchName = "").toTriage.isLeft)
    assert(ok.copy(summary = "").toTriage.isLeft)
