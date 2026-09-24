package orcacaps

/** Outside the orca package, like a flow script: the runtime's git is out of
  * reach, so a script cannot switch the run's branch or commit mid-stage.
  */
class RuntimeGitNegativeTest extends munit.FunSuite:

  test("a script cannot reach the runtime's git through its FlowContext"):
    val errors = compileErrors(
      """
      val ctx: orca.FlowContext = ???
      ctx.runtimeGit
      """
    )
    assert(errors.contains("runtimeGit"), errors)
    assert(errors.contains("cannot be accessed"), errors)

  test("a script's git cannot commit"):
    val errors = compileErrors(
      """
      val ctx: orca.FlowContext = ???
      ctx.git.commit("x")
      """
    )
    assert(errors.contains("is not a member of orca.tools.GitTool"), errors)

  test("a script cannot name the runtime's git type"):
    val errors = compileErrors(
      """
      val ctx: orca.FlowContext = ???
      ctx.git.asInstanceOf[orca.tools.RuntimeGit]
      """
    )
    assert(errors.contains("cannot be accessed"), errors)
