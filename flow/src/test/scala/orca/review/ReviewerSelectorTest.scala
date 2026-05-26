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
  SessionId
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
  def withReadOnly: LlmTool[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    new LlmCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
          def run[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type] = SessionId.fresh[BackendTag.ClaudeCode.type],
              config: LlmConfig = LlmConfig.default
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
  def withReadOnly: LlmTool[BackendTag.ClaudeCode.type] = this
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
      SelectedReviewers(List("reviewer: scala-fp", "reviewer: generic")),
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
    assertEquals(
      captured.get().map(_.availableReviewers.map(_.name)),
      Some(List("reviewer: generic"))
    )

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
