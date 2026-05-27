package orca.runner

import orca.{FlowContext, OrcaArgs, flow, fs}
import orca.tools.{FsTool}
import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeTool,
  JsonData,
  LlmCall,
  LlmConfig,
  Model,
  SessionId
}
import orca.events.{CostTracker, OrcaEvent, Usage}
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaOverridesTest extends munit.FunSuite:

  test("flow uses a custom FsTool when supplied"):
    val fake = new FsTool:
      def read(path: String): Option[String] = Some("canned content")
      def write(path: String, content: String): Unit = ()
      def list(glob: String): List[String] = List("custom")
    var observed: Option[String] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(args = OrcaArgs(), fs = Some(fake), interaction = Some(interaction)):
        observed = fs.read("ignored")
    assertEquals(observed, Some("canned content"))

  test("flow uses a custom ClaudeTool when supplied"):
    val fakeClaude = new ClaudeTool:
      val name = "fake"
      def haiku = this
      def sonnet = this
      def opus = this
      def withConfig(c: LlmConfig) = this
      def withSystemPrompt(p: String) = this
      def withName(n: String) = this
      def withReadOnly = this
      val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              p: String,
              session: SessionId[BackendTag.ClaudeCode.type] =
                SessionId.fresh[BackendTag.ClaudeCode.type],
              c: LlmConfig = LlmConfig.default
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
        claude = Some(fakeClaude),
        interaction = Some(interaction)
      ):
        observed = summon[FlowContext].claude.autonomous.run("hi")._2
    assertEquals(observed, "echo: hi")

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
