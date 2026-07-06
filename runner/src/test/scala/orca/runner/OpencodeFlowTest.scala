package orca.runner

import orca.{FlowContext, OrcaArgs, flow}
import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  SessionRegistry,
  SessionSupport
}
import orca.events.{OrcaListener, Usage}
import orca.agents.{
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  DefaultPrompts,
  InteractiveAgentCall,
  JsonData,
  AgentCall,
  AgentConfig,
  OpencodeAgent,
  SessionId,
  ToolSet,
  onWire
}
import orca.plan.{Plan, Task, Title}
import orca.tools.opencode.DefaultOpencodeAgent
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** End-to-end flow coverage for the OpenCode tool without a live server:
  *   1. the backend-agnostic Plan DSL runs through a wired `OpencodeAgent`, and
  *      2. a structured `resultAs[O]` call parses the backend's output via the
  *      real `DefaultAgentCall` (not a short-circuiting stub).
  */
class OpencodeFlowTest extends munit.FunSuite:

  // These tests drive gated LLM calls directly in the flow body (not inside a
  // `stage`), so mint the in-stage token for the suite (package `orca.runner`).
  private given orca.InStage = orca.InStage.unsafe

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body")),
    brief = "the brief"
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
      val canned = new CannedOpencode(samplePlan)
      flow(
        args = OrcaArgs(),
        agent = _.opencode,
        workDir = TempRepo.create(),
        opencode = Some(canned),
        interaction = Some(interaction)
      ):
        observed = Some(
          Plan.autonomous
            .from("implement X", summon[FlowContext].opencode)
            .value
        )
    assertEquals(observed, Some(samplePlan))

  test("resultAs[O] parses the backend output through DefaultOpencodeAgent"):
    val tool = new DefaultOpencodeAgent(
      new CannedBackend("""{"decision":"go","score":7}"""),
      AgentConfig.default,
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
    * `DefaultAgentCall` does the real parsing.
    */
  private class CannedBackend(json: String)
      extends AgentBackend[BackendTag.Opencode.type]:
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        config: AgentConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Opencode.type] =
      AgentResult(session.onWire, json, Usage(0L, 0L, None))
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        displayPrompt: String,
        config: AgentConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Opencode.type] =
      SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)
    val tag: BackendTag.Opencode.type = BackendTag.Opencode

    def enforcement(
        tools: orca.agents.ToolSet,
        autoApprove: orca.agents.AutoApprove
    ): orca.agents.Enforcement = orca.agents.Enforcement.Ignored

  private val noInteraction: Interaction = new Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): AgentResult[B] = throw new UnsupportedOperationException

  /** OpenCode-typed canned tool whose `resultAs[O]` hands back `value` directly
    * (bypassing parsing) — proves the generic Plan DSL accepts an
    * OpencodeAgent.
    */
  private class CannedOpencode[T](value: T) extends OpencodeAgent:
    val name: String = "canned"
    def anthropicOpus: OpencodeAgent = this
    def anthropicSonnet: OpencodeAgent = this
    def anthropicHaiku: OpencodeAgent = this
    def openaiGpt5: OpencodeAgent = this
    def openaiGpt5Codex: OpencodeAgent = this
    def openaiGpt5Mini: OpencodeAgent = this
    def withModel(providerModel: String): OpencodeAgent = this
    def withConfig(c: AgentConfig): OpencodeAgent = this
    def withSystemPrompt(p: String): OpencodeAgent = this
    def withName(n: String): OpencodeAgent = this
    def withTools(tools: ToolSet): OpencodeAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Opencode.type, O] =
      new AgentCall[BackendTag.Opencode.type, O]:
        val autonomous: AutonomousAgentCall[BackendTag.Opencode.type, O] =
          new AutonomousAgentCall[BackendTag.Opencode.type, O]:
            def run[I: AgentInput](
                input: I,
                session: SessionId[BackendTag.Opencode.type],
                config: AgentConfig,
                emitPrompt: Boolean
            )(using orca.InStage): (SessionId[BackendTag.Opencode.type], O) =
              (
                SessionId[BackendTag.Opencode.type]("stub-sid"),
                value.asInstanceOf[O]
              )
        def interactive: InteractiveAgentCall[BackendTag.Opencode.type, O] =
          throw new UnsupportedOperationException
