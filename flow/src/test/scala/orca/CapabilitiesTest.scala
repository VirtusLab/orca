package orca

import orca.events.EventDispatcher

/** How a `using FlowContext` requirement resolves when a [[FlowControl]] is in
  * scope (ADR 0018 §2.2).
  */
class CapabilitiesTest extends munit.FunSuite:

  private def control(userPrompt: String): TestFlowControl =
    TestFlowControl.create(new EventDispatcher(Nil), userPrompt = userPrompt)._1

  private def promptOf(using ctx: FlowContext): String = ctx.userPrompt

  test("a FlowControl alone supplies its context"):
    given FlowControl = control("from control")
    assertEquals(promptOf, "from control")

  test("a FlowContext given takes precedence over the FlowControl's context"):
    given FlowContext =
      new TestFlowContext(new EventDispatcher(Nil), userPrompt = "lexical")
    given fc: FlowControl = control("from control")
    assertEquals((promptOf, fc.context.userPrompt), ("lexical", "from control"))

end CapabilitiesTest
