package orca.review

import orca.{FlowSession, TestFlowControl}
import orca.agents.{Agent, BackendTag, SessionId}
import orca.events.EventDispatcher

/** Shared fixture construction for the `reviewAndFixLoop` tests.
  *
  * The loop now takes ONE [[FlowSession]] (coder + session bundle) and drives
  * its fix turn through the durable [[FlowSession]] door, which needs a
  * [[orca.FlowControl]] (progress store) and [[orca.WorkspaceWrite]] in scope.
  * Rather than have every call site hand-build a [[FlowSession]] via its
  * `private[orca]` constructor and a [[TestFlowControl]], both suites route
  * through here.
  */
object ReviewLoopFixture:

  /** A coder [[FlowSession]] over `agent` and a fixed session id. Constructed
    * via the `private[orca]` [[FlowSession]] ctor — legal because the tests
    * live under `orca.*` — so no production factory is widened for tests.
    */
  def coderSession(
      agent: Agent[BackendTag.ClaudeCode.type],
      id: String = "s"
  ): FlowSession[BackendTag.ClaudeCode.type] =
    new FlowSession(agent, SessionId[BackendTag.ClaudeCode.type](id))

  /** A [[TestFlowControl]] (a real temp git repo + progress store) wired to
    * `dispatcher`, so the loop's `emit`s reach the suite's listeners and the
    * fix turn's `progressStore.load()` works. Serves as the `given FlowControl`
    * for a `reviewAndFixLoop` call.
    */
  def control(dispatcher: EventDispatcher): TestFlowControl =
    TestFlowControl.create(dispatcher)._1
