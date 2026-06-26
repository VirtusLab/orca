package orca.runner

import orca.{FlowContext, OrcaArgs, flow, fs, pi}
import orca.tools.{FsTool}
import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeTool,
  JsonData,
  LlmTool,
  PiTool,
  LlmCall,
  LlmConfig,
  Model,
  OpencodeTool,
  SessionId,
  ToolSet
}
import orca.events.{CostTracker, OrcaEvent, Usage}
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaOverridesTest extends munit.FunSuite:

  // These tests drive gated LLM calls directly in the flow body (not inside a
  // `stage`), so mint the in-stage token for the suite (package `orca.runner`).
  private given orca.InStage = orca.InStage.unsafe

  // The leading-model selector defaults to `_.claude` (ADR 0018 §2.5); these
  // tests assert tool-override wiring, not LLM behaviour, so they resolve a stub
  // via a `_ => StubLlm.claude` selector.
  private val stubLead: FlowContext => LlmTool[?] = _ => StubLlm.claude

  test("flow uses a custom FsTool when supplied"):
    val fake = new FsTool:
      def read(path: String): Option[String] = Some("canned content")
      def write(path: String, content: String)(using orca.InStage): Unit = ()
      def list(glob: String): List[String] = List("custom")
    var observed: Option[String] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        fs = Some(fake),
        interaction = Some(interaction)
      ):
        observed = fs.read("ignored")
    assertEquals(observed, Some("canned content"))

  test("flow uses a custom ClaudeTool when supplied"):
    val fakeClaude = new ClaudeTool:
      val name = "fake"
      def haiku = this
      def sonnet = this
      def opus = this
      def fable = this
      def withNetworkTools(t: Seq[String]) = this
      def withConfig(c: LlmConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              c: LlmConfig,
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
            (SessionId[BackendTag.ClaudeCode.type]("fake-sid"), s"echo: $p")
      def resultAs[O: JsonData: Announce]
          : LlmCall[BackendTag.ClaudeCode.type, O] =
        ???
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = _ => fakeClaude,
        workDir = TempRepo.create(),
        claude = Some(fakeClaude),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].claude.autonomous.run("hi")._2
    assertEquals(observed, "echo: hi")

  test("flow uses a custom OpencodeTool when supplied"):
    val fakeOpencode = new OpencodeTool:
      val name = "fake"
      def anthropicOpus = this
      def anthropicSonnet = this
      def anthropicHaiku = this
      def openaiGpt5 = this
      def openaiGpt5Codex = this
      def openaiGpt5Mini = this
      def withModel(providerModel: String) = this
      def withConfig(c: LlmConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
        new AutonomousTextCall[BackendTag.Opencode.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.Opencode.type],
              c: LlmConfig,
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.Opencode.type], String) =
            (SessionId[BackendTag.Opencode.type]("fake-sid"), s"opencode: $p")
      def resultAs[O: JsonData: Announce]
          : LlmCall[BackendTag.Opencode.type, O] = ???
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        opencode = Some(fakeOpencode),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].opencode.autonomous.run("hi")._2
    assertEquals(observed, "opencode: hi")

  test("flow uses a custom PiTool when supplied"):
    val fakePi = new PiTool:
      val name = "fake-pi"
      def withConfig(c: LlmConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withTools(tools: ToolSet) = this
      val autonomous: AutonomousTextCall[BackendTag.Pi.type] =
        new AutonomousTextCall[BackendTag.Pi.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.Pi.type],
              c: LlmConfig,
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.Pi.type], String) =
            (SessionId[BackendTag.Pi.type]("fake-pi-sid"), s"pi: $p")
      def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Pi.type, O] =
        ???
    var observed: String = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        pi = Some(fakePi),
        interaction = Some(interaction)
      ):
        observed = pi.autonomous.run("hi")._2
    assertEquals(observed, "pi: hi")

  test("flow collects extra listeners alongside the interaction's"):
    val buf = new ByteArrayOutputStream()
    val tracker = new CostTracker
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(buf),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        agent = stubLead,
        workDir = TempRepo.create(),
        interaction = Some(interaction),
        extraListeners = List(tracker)
      ):
        summon[FlowContext].emit(
          OrcaEvent.TokensUsed(
            "test-agent",
            Some(Model("test-model")),
            Usage(10L, 5L, None)
          )
        )
    // TerminalInteraction ignores TokensUsed; CostTracker should accumulate.
    assertEquals(tracker.total, Usage(10L, 5L, None))
