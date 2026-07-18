package orca

import orca.events.EventDispatcher

/** Tests for the FlowControl capability type (ADR 0018 §2.2): FlowControl
  * satisfies a `using FlowContext` requirement.
  */
class CapabilitiesTest extends munit.FunSuite:

  private def stubCtrl: FlowControl =
    new TestFlowContext(new EventDispatcher(Nil))
      with FlowControl
      with StageFrames:
      def progressStore: orca.progress.ProgressStore =
        throw new NotImplementedError

  test("FlowControl satisfies a using FlowContext requirement"):
    def needsCtx(using FlowContext): Boolean = true
    given FlowControl = stubCtrl
    assert(needsCtx)

end CapabilitiesTest
