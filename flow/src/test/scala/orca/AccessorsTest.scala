package orca

import orca.events.{
  CostTracker,
  EventDispatcher,
  OrcaEvent,
  OrcaListener,
  Usage
}

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
