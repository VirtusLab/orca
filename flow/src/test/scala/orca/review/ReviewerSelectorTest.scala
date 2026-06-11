package orca.review

import orca.{FlowContext, TestFlowContext}
import orca.events.EventDispatcher
import orca.llm.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId,
  ToolSet
}
import orca.plan.Title

import java.util.concurrent.atomic.AtomicReference

/** Captures every `ReviewerSelectionRequest` handed to the picker and replies
  * with a scripted `SelectedReviewers`. Other `LlmTool` surface is unused.
  */
private class RecordingPicker(
    response: SelectedReviewers,
    captured: AtomicReference[Option[ReviewerSelectionRequest]]
) extends LlmTool[BackendTag.ClaudeCode.type]:
  val name: String = "picker"
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    new LlmCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
          def run[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: LlmConfig,
              emitPrompt: Boolean
          ): (SessionId[BackendTag.ClaudeCode.type], O) =
            input match
              case r: ReviewerSelectionRequest =>
                captured.set(Some(r))
              case _ => ()
            (
              SessionId[BackendTag.ClaudeCode.type]("picker-sid"),
              response.asInstanceOf[O]
            )
      def interactive
          : orca.llm.InteractiveLlmCall[BackendTag.ClaudeCode.type, O] = ???

/** Inert reviewer tool — just carries the name the selector dispatches on. */
private class NamedTool(override val name: String)
    extends LlmTool[BackendTag.ClaudeCode.type]:
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    ???

class ReviewerSelectorTest extends munit.FunSuite:

  private given FlowContext = new TestFlowContext(new EventDispatcher(Nil))

  private val scalaFp: LlmTool[?] = new NamedTool("reviewer: scala-fp")
  private val generic: LlmTool[?] = new NamedTool("reviewer: generic")
  private val all: List[LlmTool[?]] = List(scalaFp, generic)

  private val filePatterns =
    Map("reviewer: scala-fp" -> """\.scala$""".r)

  test("file-pattern reviewers are dropped before the picker sees them"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("scala-fp", "generic")),
      captured
    )
    val selector = ReviewerSelector.llmDriven(
      llm = picker,
      filePatterns = filePatterns
    )
    val picked = selector(Nil, all, Title("any"), List("src/lib.rs"))
    // Even though the picker tried to include scala-fp, it was never offered
    // and the post-filter drops it from the result.
    assertEquals(picked.map(_.name), List("reviewer: generic"))
    // The picker is shown bare slugs, not the `reviewer: ` cost-attribution
    // prefix.
    assertEquals(
      captured.get().map(_.availableReviewers.map(_.name)),
      Some(List("generic"))
    )

  test("picker reply matches whether it echoes the slug or the full name"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    // Mixed: slug for one, full prefixed name for the other — both resolve.
    val picker = new RecordingPicker(
      SelectedReviewers(List("generic", "reviewer: scala-fp")),
      captured
    )
    val selector = ReviewerSelector.llmDriven(llm = picker)
    val picked =
      selector(Nil, all, Title("any"), List("src/main/scala/Foo.scala"))
    assertEquals(
      picked.map(_.name).toSet,
      Set("reviewer: generic", "reviewer: scala-fp")
    )

  test(
    "empty picker selection falls back to all eligible (review never skipped)"
  ):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(SelectedReviewers(Nil), captured)
    val selector = ReviewerSelector.llmDriven(
      llm = picker,
      filePatterns = filePatterns
    )
    // scala-fp is filtered out for a .rs change; generic is eligible. The
    // picker picks nothing, so the floor falls back to the eligible set.
    val picked = selector(Nil, all, Title("any"), List("src/lib.rs"))
    assertEquals(picked.map(_.name), List("reviewer: generic"))

  test("file-pattern reviewers are offered when matching files are present"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("reviewer: scala-fp", "reviewer: generic")),
      captured
    )
    val selector = ReviewerSelector.llmDriven(
      llm = picker,
      filePatterns = filePatterns
    )
    val picked = selector(
      Nil,
      all,
      Title("any"),
      List("src/main/scala/Foo.scala")
    )
    assertEquals(
      picked.map(_.name),
      List("reviewer: scala-fp", "reviewer: generic")
    )

  test("selector skips the picker LLM entirely when no reviewer is eligible"):
    val captured = new AtomicReference[Option[ReviewerSelectionRequest]](None)
    val picker = new RecordingPicker(
      SelectedReviewers(List("reviewer: scala-fp")),
      captured
    )
    val onlyScala = List(scalaFp)
    val selector = ReviewerSelector.llmDriven(
      llm = picker,
      filePatterns = filePatterns
    )
    val picked = selector(Nil, onlyScala, Title("any"), List("src/lib.rs"))
    assertEquals(picked, Nil)
    assertEquals(captured.get(), None)
