package orca.pr

import orca.{FlowContext, TestFlowContext}
import orca.events.EventDispatcher

class SummarisePrTest extends munit.FunSuite:

  // `summarisePr` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  test("the diff keeps a line whose first non-blank character is `|`"):
    given FlowContext = new TestFlowContext(new EventDispatcher(Nil))
    val agent = new StubSummariser()
    val _ = summarisePr(agent.agent, diff = "+first line\n |context with pipe")
    assert(
      agent.captured.contains("\n |context with pipe"),
      s"the `|` line must reach the summariser intact, got: ${agent.captured}"
    )
