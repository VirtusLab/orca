package orca.review

import orca.{FlowContext, TestFlowContext}
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.agents.{
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
  SessionId,
  ToolSet
}
import orca.plan.Title

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** Captures every `ReviewerSelectionRequest` handed to the picker and replies
  * with a scripted `SelectedReviewers`, counting each call. Other `Agent`
  * surface is unused.
  */
private class RecordingPicker(
    response: SelectedReviewers,
    captured: AtomicReference[Option[ReviewerSelectionRequest]],
    calls: AtomicInteger = new AtomicInteger(0)
) extends Agent[BackendTag.ClaudeCode.type]:
  val name: String = "picker"
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): O =
            val _ = calls.incrementAndGet()
            input match
              case r: ReviewerSelectionRequest =>
                captured.set(Some(r))
              case _ => ()
            response.asInstanceOf[O]
      def interactive
          : orca.agents.InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        ???

/** Inert reviewer tool — just carries the name the selector dispatches on. */
private class NamedTool(override val name: String)
    extends Agent[BackendTag.ClaudeCode.type]:
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    ???

class ReviewerSelectorTest extends munit.FunSuite:

  private given FlowContext = new TestFlowContext(new EventDispatcher(Nil))

  // `agentDriven`'s effects are gated on `InStage` at `prepare` time; mint the
  // token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val scalaFp: RosterEntry[?] =
    RosterEntry.wrap(new NamedTool("scala-fp"))
  private val generic: RosterEntry[?] =
    RosterEntry.wrap(new NamedTool("generic"))
  private val all: List[RosterEntry[?]] = List(scalaFp, generic)

  private val filePatterns =
    Map("scala-fp" -> """\.scala$""".r)

  private def reported(e: RosterEntry[?]): ReviewBatch =
    ReviewBatch(
      List(
        e -> ReviewResult(
          List(
            ReviewIssue(
              severity = Severity.Warning,
              confidence = 1.0,
              title = Title("found something"),
              description = "found something",
              location = None,
              suggestion = None
            )
          )
        )
      )
    )

  test("file-pattern reviewers are dropped before the picker sees them"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp", "generic")),
      captured
    )
    val selector = ReviewerSelector.agentDriven(
      agent = picker,
      filePatterns = filePatterns
    )
    val picked =
      selector.prepare(all, Title("any"), List("src/lib.rs"))(Nil)
    // Even though the picker tried to include scala-fp, it was never offered
    // and the post-filter drops it from the result.
    assertEquals(picked.map(_.name), List("generic"))
    // The picker is shown bare slugs — no `reviewer: ` cost-attribution prefix
    // reaches it.
    assertEquals(
      captured.get().map(_.availableReviewers.map(_.name)),
      Some(List("generic"))
    )

  test("picker reply resolves reviewers by bare slug"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("generic", "scala-fp")),
      captured
    )
    val selector = ReviewerSelector.agentDriven(agent = picker)
    val picked =
      selector.prepare(all, Title("any"), List("src/main/scala/Foo.scala"))(Nil)
    assertEquals(
      picked.map(_.name).toSet,
      Set("generic", "scala-fp")
    )

  test(
    "empty picker selection falls back to all eligible (review never skipped)"
  ):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(SelectedReviewers(Nil), captured)
    val selector = ReviewerSelector.agentDriven(
      agent = picker,
      filePatterns = filePatterns
    )
    // scala-fp is filtered out for a .rs change; generic is eligible. The
    // picker picks nothing, so the floor falls back to the eligible set.
    val picked =
      selector.prepare(all, Title("any"), List("src/lib.rs"))(Nil)
    assertEquals(picked.map(_.name), List("generic"))

  test("file-pattern reviewers are offered when matching files are present"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp", "generic")),
      captured
    )
    val selector = ReviewerSelector.agentDriven(
      agent = picker,
      filePatterns = filePatterns
    )
    val picked = selector.prepare(
      all,
      Title("any"),
      List("src/main/scala/Foo.scala")
    )(Nil)
    assertEquals(
      picked.map(_.name),
      List("scala-fp", "generic")
    )

  test("an empty diff keeps file-pattern reviewers eligible"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp")),
      captured
    )
    val selector = ReviewerSelector.agentDriven(
      agent = picker,
      filePatterns = filePatterns
    )
    // No changed files means the diff didn't say which files changed, not that
    // none did: the pre-filter is skipped, so scala-fp reaches the picker.
    val picked = selector.prepare(all, Title("any"), Nil)(Nil)
    assertEquals(
      captured.get().map(_.availableReviewers.map(_.name)),
      Some(List("scala-fp", "generic"))
    )
    assertEquals(picked.map(_.name), List("scala-fp"))

  test("an empty diff announces the skipped file-pattern filter"):
    val steps = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val listener: OrcaListener = (e: OrcaEvent) =>
      e match
        case OrcaEvent.Step(msg) => steps.add(msg): Unit
        case _                   => ()
    val listening: FlowContext =
      new TestFlowContext(new EventDispatcher(List(listener)))
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val selector = ReviewerSelector.agentDriven(
      agent =
        new RecordingPicker(SelectedReviewers(List("scala-fp")), captured),
      filePatterns = filePatterns
    )
    val _ = selector.prepare(all, Title("any"), Nil)(using
      listening,
      summon[orca.InStage]
    )(Nil)
    assertEquals(
      steps.toArray.toList.map(_.toString),
      List(
        "reviewer selection: no changed files to match against; keeping " +
          "1 file-gated reviewer(s) eligible (scala-fp)"
      )
    )

  test("selector skips the picker LLM entirely when no reviewer is eligible"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp")),
      captured
    )
    val onlyScala = List(scalaFp)
    val selector = ReviewerSelector.agentDriven(
      agent = picker,
      filePatterns = filePatterns
    )
    val picked =
      selector.prepare(onlyScala, Title("any"), List("src/lib.rs"))(Nil)
    assertEquals(picked, Nil)
    assertEquals(captured.get(), None)

  test(
    "agentDriven queries the picker exactly once per prepare, across many rounds"
  ):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val calls = new AtomicInteger(0)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp", "generic")),
      captured,
      calls
    )
    val selector = ReviewerSelector.agentDriven(agent = picker)
    // ONE prepare — the single LLM call happens here.
    val selectRound =
      selector.prepare(all, Title("any"), List("src/main/scala/Foo.scala"))
    // Apply the pure per-round function to three different histories, as the
    // loop would over successive iterations.
    val r1 = selectRound(Nil)
    val r2 = selectRound(List(ReviewBatch(Nil)))
    val r3 = selectRound(List(ReviewBatch(Nil), ReviewBatch(Nil)))
    assertEquals(
      r1.map(_.name),
      List("scala-fp", "generic")
    )
    assertEquals(r2, r1)
    assertEquals(r3, r1)
    assertEquals(calls.get(), 1)

  test("a selector value is reusable across loops"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val calls = new AtomicInteger(0)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp", "generic")),
      captured,
      calls
    )
    val selector = ReviewerSelector.agentDriven(agent = picker)
    // Two loops reuse the same selector value — each `prepare` re-queries the
    // picker (fresh pick per loop, no cross-loop cache).
    val _ = selector.prepare(all, Title("loop-1"), List("a.scala"))(Nil)
    val _ = selector.prepare(all, Title("loop-2"), List("b.scala"))(Nil)
    assertEquals(calls.get(), 2)

  test("narrowing re-runs only the reviewers that reported last round"):
    val selector = ReviewerSelector.narrowingAcrossRounds(
      ReviewerSelector.allEveryRound,
      filePatterns
    )
    // A Rust change, so scala-fp has no file claim on the round: only its
    // silence in round one decides it.
    val selectRound = selector.prepare(all, Title("any"), List("src/lib.rs"))
    assertEquals(selectRound(Nil).map(_.name), List("scala-fp", "generic"))
    assertEquals(
      selectRound(List(reported(generic))).map(_.name),
      List("generic")
    )

  test("narrowing keeps a file-pattern reviewer whose files are in the diff"):
    val selector = ReviewerSelector.narrowingAcrossRounds(
      ReviewerSelector.allEveryRound,
      filePatterns
    )
    val selectRound =
      selector.prepare(all, Title("any"), List("src/main/scala/Foo.scala"))
    // Neither reviewer reported last round; scala-fp stays on because the
    // fixer keeps editing files it owns.
    assertEquals(
      selectRound(List(ReviewBatch(Nil))).map(_.name),
      List("scala-fp")
    )
