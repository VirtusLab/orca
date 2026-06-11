package orca.runner

import orca.{FlowContext, OrcaArgs, flow}
import orca.backend.{Conversation, Interaction, LlmBackend, LlmResult}
import orca.events.{OrcaListener, Usage}
import orca.llm.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  BackendTag,
  DefaultPrompts,
  InteractiveLlmCall,
  JsonData,
  LlmCall,
  LlmConfig,
  OpencodeTool,
  SessionId,
  ToolSet
}
import orca.plan.{Plan, Task, Title}
import orca.tools.opencode.DefaultOpencodeTool
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** End-to-end flow coverage for the OpenCode tool without a live server:
  *   1. the backend-agnostic Plan DSL runs through a wired `OpencodeTool`, and
  *      2. a structured `resultAs[O]` call parses the backend's output via the
  *      real `DefaultLlmCall` (not a short-circuiting stub).
  */
class OpencodeFlowTest extends munit.FunSuite:

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body"))
  )

  test(
    "Plan.autonomous.from runs the real Plan DSL through the wired opencode tool"
  ):
    var observed: Option[Plan] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        opencode = Some(new CannedOpencode(samplePlan)),
        interaction = Some(interaction)
      ):
        observed = Some(
          Plan.autonomous
            .from("implement X", summon[FlowContext].opencode)
            .value
        )
    assertEquals(observed, Some(samplePlan))

  test("resultAs[O] parses the backend output through DefaultOpencodeTool"):
    val tool = new DefaultOpencodeTool(
      new CannedBackend("""{"decision":"go","score":7}"""),
      LlmConfig.default,
      DefaultPrompts,
      os.temp.dir(),
      OrcaListener.noop,
      noInteraction
    )
    val (_, v) = tool.resultAs[Verdict].autonomous.run("assess")
    assertEquals(v, Verdict("go", 7))

  // --- doubles ---

  private case class Verdict(decision: String, score: Int) derives JsonData

  /** Returns a fixed JSON string as the autonomous output; the tool's
    * `DefaultLlmCall` does the real parsing.
    */
  private class CannedBackend(json: String)
      extends LlmBackend[BackendTag.Opencode.type]:
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        config: LlmConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): LlmResult[BackendTag.Opencode.type] =
      LlmResult(session, json, Usage(0L, 0L, None))
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        displayPrompt: String,
        config: LlmConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    ): Conversation[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException

  private val noInteraction: Interaction = new Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): LlmResult[B] = throw new UnsupportedOperationException

  /** OpenCode-typed canned tool whose `resultAs[O]` hands back `value` directly
    * (bypassing parsing) — proves the generic Plan DSL accepts an OpencodeTool.
    */
  private class CannedOpencode[T](value: T) extends OpencodeTool:
    val name: String = "canned"
    def anthropicOpus: OpencodeTool = this
    def anthropicSonnet: OpencodeTool = this
    def anthropicHaiku: OpencodeTool = this
    def openaiGpt5: OpencodeTool = this
    def openaiGpt5Codex: OpencodeTool = this
    def openaiGpt5Mini: OpencodeTool = this
    def withModel(providerModel: String): OpencodeTool = this
    def withConfig(c: LlmConfig): OpencodeTool = this
    def withSystemPrompt(p: String): OpencodeTool = this
    def withName(n: String): OpencodeTool = this
    def withTools(tools: ToolSet): OpencodeTool = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Opencode.type, O] =
      new LlmCall[BackendTag.Opencode.type, O]:
        val autonomous: AutonomousLlmCall[BackendTag.Opencode.type, O] =
          new AutonomousLlmCall[BackendTag.Opencode.type, O]:
            def run[I: AgentInput](
                input: I,
                session: SessionId[BackendTag.Opencode.type],
                config: LlmConfig,
                emitPrompt: Boolean
            ): (SessionId[BackendTag.Opencode.type], O) =
              (
                SessionId[BackendTag.Opencode.type]("stub-sid"),
                value.asInstanceOf[O]
              )
        def interactive: InteractiveLlmCall[BackendTag.Opencode.type, O] =
          throw new UnsupportedOperationException
