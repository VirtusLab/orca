package orca

import orca.events.EventDispatcher

class AccessorsTest extends munit.FunSuite:

  private def ctxWith(prompt: String): FlowContext =
    new TestFlowContext(new EventDispatcher(Nil), userPrompt = prompt)

  test("userPrompt resolves against the ambient FlowContext"):
    given FlowContext = ctxWith("make it so")
    assertEquals(userPrompt, "make it so")

  test("every tool accessor forwards to the context"):
    given FlowContext = ctxWith("")
    // TestFlowContext stubs all five tools with NotImplementedError; each
    // accessor must therefore throw rather than return some default.
    val _ = intercept[NotImplementedError](claude)
    val _ = intercept[NotImplementedError](codex)
    val _ = intercept[NotImplementedError](git)
    val _ = intercept[NotImplementedError](gh)
    val _ = intercept[NotImplementedError](fs)

  test("the three role accessors resolve against the ambient FlowContext"):
    given FlowContext = ctxWith("")
    // TestFlowContext backs all three roles with the (stubbed) lead, so each
    // accessor must throw the same way `agent` does rather than return some
    // default.
    val _ = intercept[NotImplementedError](planningAgent)
    val _ = intercept[NotImplementedError](codingAgent)
    val _ = intercept[NotImplementedError](reviewAgent)

  /** Compile-only: a session minted from `codingAgent` must type as
    * `FlowSession[ctx.CodeB]`, matching the coding role's pinned backend — a
    * regression here would erase the type and stop sessions from threading
    * through `session.run`. Typechecking alone is the assertion.
    */
  def codingAgentSessionThreads(using
      ctx: FlowContext,
      fc: FlowControl
  ): FlowSession[ctx.CodeB] =
    codingAgent.session("impl", seed = "seed")
