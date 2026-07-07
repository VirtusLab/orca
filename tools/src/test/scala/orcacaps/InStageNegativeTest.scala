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
    // `InStage`'s `@implicitNotFound` makes the error user-facing — it tells the
    // author to move the call into a `stage(...)` rather than naming the internal
    // `InStage` type. Pin that message (and that the cryptic default is gone).
    // NB: until Epic 0.4 migrates mutating tool methods onto `WorkspaceWrite`,
    // `git.commit` is still gated on `InStage`, so this surfaces the InStage
    // message; the helper clause `(using InStage)` is its stable signature.
    assert(
      errors.contains("inside a `stage(...)` body") &&
        errors.contains("(using InStage)"),
      s"expected the friendly stage-required message, got: $errors"
    )
    assert(
      !errors.contains("No given instance of type orca.InStage"),
      s"the cryptic default message should be replaced by @implicitNotFound, got: $errors"
    )

end InStageNegativeTest
