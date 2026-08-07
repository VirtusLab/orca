package orca.review

import orca.{FlowSession, StackSettings, TestFlowControl}
import orca.agents.{Agent, BackendTag, SessionId}
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}

/** Shared fixture construction for the `reviewAndFixLoop` tests.
  *
  * The loop takes one [[FlowSession]] (coder + session bundle) and drives its
  * fix turn through the durable [[FlowSession]] door, which needs a
  * [[orca.FlowControl]] (progress store) and [[orca.WorkspaceWrite]] in scope.
  */
object ReviewLoopFixture:

  /** Collects every `Step` message emitted through [[dispatcher]]. Hand the
    * dispatcher to whichever context the test needs — [[control]] here, or a
    * bare `TestFlowContext` for a selector.
    */
  class StepCapture:
    private val steps = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    private val listener: OrcaListener = (e: OrcaEvent) =>
      e match
        case OrcaEvent.Step(msg) => steps.add(msg): Unit
        case _                   => ()
    val dispatcher: EventDispatcher = new EventDispatcher(List(listener))
    def messages: List[String] = steps.toArray.toList.map(_.toString)

  /** A coder [[FlowSession]] over `agent` and a fixed session id, built via the
    * `private[orca]` ctor so no production factory is widened for tests.
    */
  def coderSession(
      agent: Agent[BackendTag.ClaudeCode.type],
      id: String = "s"
  ): FlowSession[BackendTag.ClaudeCode.type] =
    new FlowSession(agent, SessionId[BackendTag.ClaudeCode.type](id))

  /** A [[TestFlowControl]] (a real temp git repo + progress store) wired to
    * `dispatcher`, so the loop's `emit`s reach the suite's listeners and the
    * fix turn's `progressStore.load()` works. Serves as the `given FlowControl`
    * for a `reviewAndFixLoop` call. `lead` wires the context's lead agent —
    * needed by `ReviewerSelector.default`, whose picker resolves as
    * `ctx.reviewAgent.cheap`, and by `Configured.FromSettings` lint resolution
    * (`Lint(stackSettings.lint, ctx.reviewAgent.cheap)`). `stackSettings` seeds
    * the context's resolved settings for the `FromSettings` tests.
    */
  def control(
      dispatcher: EventDispatcher,
      lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
      stackSettings: StackSettings = StackSettings.empty
  ): TestFlowControl =
    TestFlowControl
      .create(dispatcher, lead = lead, stackSettings = stackSettings)
      ._1
