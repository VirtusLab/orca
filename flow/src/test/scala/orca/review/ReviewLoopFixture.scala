package orca.review

import orca.StagePath
import orca.{
  FlowContext,
  FlowSession,
  InStage,
  StackSettings,
  TestFlowControl,
  TestRun
}
import orca.agents.{Agent, BackendTag, JsonData, SessionId, SessionKey}
import orca.backend.{AgentResult, IdScheme, SessionSupport, TurnRequest}
import orca.testkit.{PassthroughPrompts, ScriptedBackend, TestAgent}
import orca.AgentTurnFailed
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener, TurnDebit}
import orca.plan.{Task, Title}
import orca.gitref.CommitHash

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

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
      coder: FakeAgent,
      id: String = "s"
  ): FlowSession =
    new FlowSession(
      coder.agent.chat(SessionId[BackendTag.ClaudeCode.type](id)),
      SessionKey(name = "coder", stage = StagePath.FlowBody)
    )

  /** A [[TestRun]] (a real temp git repo + progress store) wired to
    * `dispatcher`, so the loop's `emit`s reach the suite's listeners and the
    * fix turn's `progressStore.load()` works. Supplies the givens for a
    * `reviewAndFixLoop` call. `lead` wires the context's lead agent — needed by
    * `ReviewerSelector.default`, whose picker resolves as
    * `ctx.reviewAgent.cheap`, and by `Configured.FromSettings` lint resolution
    * (`Lint(stackSettings.lint, ctx.reviewAgent.cheap)`). `stackSettings` seeds
    * the context's resolved settings for the `FromSettings` tests, and
    * `userPrompt` seeds the context's run prompt.
    */
  def run(
      dispatcher: EventDispatcher,
      lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
      stackSettings: StackSettings = StackSettings.empty,
      userPrompt: String = "p"
  ): TestRun =
    TestRun.create(
      dispatcher,
      userPrompt = userPrompt,
      lead = lead,
      stackSettings = stackSettings
    )

  /** Like [[run]], but the run carries no starting commit: no header recorded
    * one, or the one it recorded was dropped as unusable. What
    * `ReviewDiff.WholeRun` has to cope with.
    */
  def runWithoutStartingCommit(dispatcher: EventDispatcher): TestRun =
    TestRun.create(dispatcher, startingCommitUsable = false)

  /** Like [[run]], but the recorded starting commit resolves to nothing in the
    * repo — what a mid-run rebase (or a fresh clone) leaves behind for
    * `ReviewDiff.WholeRun`'s review-time ancestor probe.
    */
  def runWithUnusableStartingCommit(dispatcher: EventDispatcher): TestRun =
    val base = TestRun.create(dispatcher)
    base.copy(control =
      new TestFlowControl(
        base.control.progressStore,
        base.control.sessionStore,
        CommitHash.from("0" * 40)
      )
    )

/** A [[Task]] carrying only a title — what a test that doesn't exercise the
  * description passes for `reviewAndFixLoop`'s `task`.
  */
private[review] def titled(title: String): Task = Task(Title(title), "")

/** One structured reply a [[FakeAgent]] answers with — any `JsonData` value
  * converts to one, so a script can mix result types.
  */
private[review] final class Reply(val json: String)

private[review] object Reply:
  given [T: JsonData]: Conversion[T, Reply] = t =>
    Reply(ScriptedBackend.json(t))

/** An agent replying with `outputs` in order, recording each turn; a turn past
  * the end fails without a retry, which is how a test pins that an agent must
  * never run. `onRun` fires before each reply. Structured inputs reach the
  * backend unwrapped ([[PassthroughPrompts]]), so [[seenPrompts]] holds what
  * the caller sent.
  */
private[review] class FakeAgent(
    val name: String,
    outputs: List[Reply] = Nil,
    onRun: () => Unit = () => (),
    sessions: SessionSupport[BackendTag.ClaudeCode.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
):
  private val remaining = new ConcurrentLinkedQueue[Reply](outputs.asJava)
  private val turns =
    new ConcurrentLinkedQueue[TurnRequest[BackendTag.ClaudeCode.type]]()
  private val tokens = new ConcurrentLinkedQueue[OrcaEvent.UnpricedTurn]()

  val agent: Agent[BackendTag.ClaudeCode.type] = TestAgent(
    new ScriptedBackend(BackendTag.ClaudeCode, sessions):
      protected def reply(
          turn: TurnRequest[BackendTag.ClaudeCode.type]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        turns.add(turn): Unit
        onRun()
        Option(remaining.poll()) match
          case Some(r) => ScriptedBackend.result(r.json)
          case None =>
            throw new AgentTurnFailed(
              s"$name: no reply scripted",
              TurnDebit.Unobserved
            )
    ,
    name,
    events = {
      case t: OrcaEvent.UnpricedTurn => tokens.add(t): Unit
      case _                         => ()
    },
    prompts = PassthroughPrompts
  )

  /** Session ids this agent was called with, in invocation order. Tests assert
    * the loop threaded a stable id across iterations.
    */
  def seenSessions: List[SessionId[BackendTag.ClaudeCode.type]] =
    turns.asScala.toList.map(_.session)

  /** Inputs this agent was sent, in invocation order. */
  def seenPrompts: List[String] = turns.asScala.toList.map(_.prompt)

  /** The `(name, role)` each turn was attributed to, in invocation order. */
  def seenIdentities: List[(String, Option[String])] =
    tokens.asScala.toList.map(t => (t.agent, t.role))

/** A finding whose title doubles as its description, with no location or
  * suggestion — the shape the review tests assert on.
  */
private[review] def finding(desc: String): ReviewFinding =
  ReviewFinding(
    title = Title(desc),
    description = desc,
    location = None,
    suggestion = None,
    reopens = None
  )

/** A [[ReviewerAgent]] over `agent`, its definition named after the agent —
  * what the loop entry points take. `description` and `filePattern` are what
  * [[ReviewerSelector.agentDriven]] reads off the definition. The system prompt
  * is empty: a fake agent ignores it.
  */
private[review] def asReviewer(
    fake: FakeAgent,
    description: String = "reviews things",
    filePattern: Option[Regex] = None
): ReviewerAgent[BackendTag.ClaudeCode.type] =
  val agent = fake.agent
  ReviewerAgent(
    Reviewer(
      ReviewerSlug(agent.name),
      description,
      systemPrompt = "",
      filePattern
    ),
    agent
  )

/** A [[ReviewerSelector]] that does nothing at prepare time and narrows each
  * round with `narrow(roster, history)`.
  */
private[review] def selector(
    narrow: (List[RosterEntry], List[ReviewBatch]) => List[RosterEntry]
): ReviewerSelector = new ReviewerSelector:
  def prepare(
      all: List[RosterEntry],
      taskTitle: Title,
      changedFiles: List[String]
  )(using FlowContext, InStage) =
    history => narrow(all, history)
