package orcacaps

/** Negative compile test: verifies that InStage.unsafe / WorkspaceWrite.unsafe
  * are not accessible from outside the orca package (ADR 0018 §2.2, §6). This
  * file is intentionally in package orcacaps — not orca — so that private[orca]
  * members are hidden.
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

  test("WorkspaceWrite.unsafe is not accessible outside the orca package"):
    val errors = compileErrors("orca.WorkspaceWrite.unsafe")
    assert(
      errors.nonEmpty,
      "expected a compile error when accessing WorkspaceWrite.unsafe outside orca"
    )
    assert(
      errors.contains("access") || errors.contains("private") || errors
        .contains("cannot be accessed"),
      s"expected error to mention an access/visibility restriction, got: $errors"
    )

  test(
    "a gated git mutation does NOT compile without a WorkspaceWrite in scope"
  ):
    // The real B2 enforcement, post-0.4 split: with a `GitTool` in scope but NO
    // `WorkspaceWrite`, `git.commit(...)` must fail to compile, pointing at the
    // missing capability. Proves the gate works end-to-end — a workspace
    // mutation is impossible outside a stage.
    val errors = compileErrors(
      """
      val git: orca.tools.GitTool = ???
      git.commit("x")
      """
    )
    assert(
      errors.nonEmpty,
      "expected a compile error for git.commit without a WorkspaceWrite"
    )
    // `WorkspaceWrite`'s `@implicitNotFound` makes the error user-facing — it
    // tells the author to move the call into a `stage(...)` body (not a `fork`
    // within one), rather than naming the internal `WorkspaceWrite` type. Pin
    // that message (and that the cryptic default is gone) — and that it is
    // DISTINCT from the InStage message below, since the two tokens now gate
    // different effects.
    assert(
      errors.contains(
        "must be made inside a `stage(...)` body"
      ) && errors.contains("must NOT be captured into a `fork`") &&
        errors.contains("(using WorkspaceWrite)"),
      s"expected the friendly workspace-write-required message, got: $errors"
    )
    assert(
      !errors.contains("No given instance of type orca.WorkspaceWrite"),
      s"the cryptic default message should be replaced by @implicitNotFound, got: $errors"
    )

  test("a gated LLM run does NOT compile without an InStage in scope"):
    // The InStage half of the same enforcement: with an `Agent` in scope but NO
    // `InStage`, `agent.autonomous.run(...)` must fail to compile. Proves the
    // LLM-call gate still works after the split — only a `stage(...)` body can
    // spend tokens.
    val errors = compileErrors(
      """
      val agent: orca.agents.Agent[orca.agents.BackendTag.ClaudeCode.type] = ???
      agent.autonomous.run("hi")
      """
    )
    assert(
      errors.nonEmpty,
      "expected a compile error for agent.autonomous.run without an InStage"
    )
    // Pin the InStage message distinctly from WorkspaceWrite's: it talks about
    // LLM runs and a stage, but says nothing about forks (LLM calls MAY cross
    // a fork boundary — that's the whole point of the split).
    assert(
      errors.contains("LLM runs must be made inside a `stage(...)` body") &&
        errors.contains("(using InStage)"),
      s"expected the friendly stage-required message, got: $errors"
    )
    assert(
      !errors.contains("No given instance of type orca.InStage"),
      s"the cryptic default message should be replaced by @implicitNotFound, got: $errors"
    )
    assert(
      !errors.contains("must NOT be captured into a `fork`"),
      s"the InStage message must not borrow WorkspaceWrite's fork wording, got: $errors"
    )

end InStageNegativeTest
