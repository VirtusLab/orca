package orcacaps

/** Negative compile test: verifies that InStage.unsafe is not accessible from
  * outside the orca package (ADR 0018 §2.2). This file is intentionally in
  * package orcacaps — not orca — so that private[orca] members are hidden.
  */
class InStageNegativeTest extends munit.FunSuite:

  test("InStage.unsafe is not accessible outside the orca package"):
    val errors = compileErrors("orca.InStage.unsafe")
    assert(
      errors.nonEmpty,
      "expected a compile error when accessing InStage.unsafe outside orca"
    )
    assert(
      errors.contains("access") || errors.contains("private") || errors
        .contains("cannot be accessed"),
      s"expected error to mention an access/visibility restriction, got: $errors"
    )

  test("a gated git mutation does NOT compile without an InStage in scope"):
    // The real B2 enforcement: with a `GitTool` in scope but NO `InStage`,
    // `git.commit(...)` must fail to compile, pointing at the missing capability.
    // Proves the gate works end-to-end — mutation is impossible outside a stage.
    val errors = compileErrors(
      """
      val git: orca.tools.GitTool = ???
      git.commit("x")
      """
    )
    assert(
      errors.nonEmpty,
      "expected a compile error for git.commit without an InStage"
    )
    assert(
      errors.contains("InStage"),
      s"expected the error to mention the missing InStage, got: $errors"
    )

end InStageNegativeTest
