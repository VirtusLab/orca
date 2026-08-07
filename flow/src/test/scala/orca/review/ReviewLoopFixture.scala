package orca.review

import orca.{FlowContext, FlowSession, InStage, StackSettings, TestFlowControl}
import orca.agents.{
  Agent,
  AgentCall,
  AgentConfig,
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  SessionId,
  ToolSet
}
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.plan.Title

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

/** Agent stub base: identity plus the `with*` no-ops every review-loop stub
  * repeats; subclasses supply only `resultAs`.
  */
private[review] abstract class StubAgent(override val name: String)
    extends Agent[BackendTag.ClaudeCode.type]:
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this

/** Fake AgentCall whose `autonomous.run` drains a scripted sequence of outputs
  * in order. `seenSessions` records each call's session id so tests can assert
  * "fresh on first, same id thereafter."; `seenPrompts` records what the caller
  * sent. `onRun` fires before each reply, for simulating an agent that touches
  * the working tree.
  */
private[review] class FakeAgentCall[O](
    outputs: Iterator[Any],
    onRun: () => Unit
) extends AgentCall[BackendTag.ClaudeCode.type, O]:

  /** Session ids the LLM was called with, in invocation order. */
  val seenSessions = new java.util.concurrent.atomic.AtomicReference[
    List[SessionId[BackendTag.ClaudeCode.type]]
  ](Nil)

  /** Rendered inputs the LLM was called with, in invocation order. */
  val seenPrompts =
    new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)

  val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
    new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
      private[orca] def runWithSession[I: AgentInput](
          input: I,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: Option[AgentConfig],
          emitPrompt: Boolean
      )(using orca.InStage): O =
        val _ = seenSessions.updateAndGet(session :: _)
        val _ = seenPrompts.updateAndGet(
          summon[AgentInput[I]].serialize(input) :: _
        )
        onRun()
        outputs.next().asInstanceOf[O]
  def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] = ???

/** An agent replying with `outputs` in order; a call past the end throws, which
  * is how a test pins that a stub must never run.
  */
private[review] class FakeAgent(
    name: String,
    outputs: List[Any] = Nil,
    onRun: () => Unit = () => ()
) extends StubAgent(name):
  private val it = outputs.iterator
  val fakeCall: FakeAgentCall[Any] = new FakeAgentCall[Any](it, onRun)

  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    fakeCall.asInstanceOf[AgentCall[BackendTag.ClaudeCode.type, O]]

  /** Session ids this tool was called with, in invocation order. Tests assert
    * the loop threaded a stable id across iterations.
    */
  def seenSessions: List[SessionId[BackendTag.ClaudeCode.type]] =
    fakeCall.seenSessions.get().reverse

  /** Rendered inputs this tool was called with, in invocation order. */
  def seenPrompts: List[String] = fakeCall.seenPrompts.get().reverse

/** A finding whose title doubles as its description, with no location or
  * suggestion — the shape the review tests assert on.
  */
private[review] def issue(
    desc: String,
    confidence: Double = 1.0,
    severity: Severity = Severity.Warning
): ReviewIssue =
  ReviewIssue(
    severity = severity,
    confidence = Confidence.orThrow(confidence),
    title = Title(desc),
    description = desc,
    location = None,
    suggestion = None
  )

/** A [[ReviewerSelector]] that does nothing at prepare time and narrows each
  * round with `narrow(roster, history)`.
  */
private[review] def selector(
    narrow: (List[RosterEntry[?]], List[ReviewBatch]) => List[RosterEntry[?]]
): ReviewerSelector = new ReviewerSelector:
  def prepare(
      all: List[RosterEntry[?]],
      taskTitle: Title,
      changedFiles: List[String]
  )(using FlowContext, InStage) =
    history => narrow(all, history)
