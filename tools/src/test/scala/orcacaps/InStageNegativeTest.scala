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

end InStageNegativeTest
