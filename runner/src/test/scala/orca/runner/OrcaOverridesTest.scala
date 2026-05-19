package orca.runner

import orca.{FlowContext, OrcaArgs, flow, fs}
import orca.tools.fs.{FsTool}
import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeTool,
  JsonData,
  LlmCall,
  LlmConfig,
  SessionId
}
import orca.events.{CostTracker, OrcaEvent, Usage}
import _root_.orca.runner.terminal.TerminalInteraction

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaOverridesTest extends munit.FunSuite:

  private def silentInteraction: TerminalInteraction =
    new TerminalInteraction(
      new PrintStream(new ByteArrayOutputStream()),
      useColor = false,
      animated = false
    )

  test("flow uses a custom FsTool when supplied"):
    val fake = new FsTool:
      def read(path: String): Option[String] = Some("canned content")
      def write(path: String, content: String): Unit = ()
      def list(glob: String): List[String] = List("custom")
    var observed: Option[String] = None
    flow(
      args = OrcaArgs(),
      fs = Some(fake),
      interaction = Some(silentInteraction)
    ) {
      observed = fs.read("ignored")
    }
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
      val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(p: String, c: LlmConfig = LlmConfig.default): String =
            s"echo: $p"
          def startSession(
              p: String,
              c: LlmConfig = LlmConfig.default
          ): (SessionId[BackendTag.ClaudeCode.type], String) = ???
          def continueSession(
              s: SessionId[BackendTag.ClaudeCode.type],
              p: String,
              c: LlmConfig = LlmConfig.default
          ): String = ???
      def resultAs[O: JsonData: Announce]
          : LlmCall[BackendTag.ClaudeCode.type, O] =
        ???
    var observed: String = ""
    flow(
      args = OrcaArgs(),
      claude = Some(fakeClaude),
      interaction = Some(silentInteraction)
    ) {
      observed = summon[FlowContext].claude.autonomous.run("hi")
    }
    assertEquals(observed, "echo: hi")

  test("flow collects extra listeners alongside the interaction's"):
    val buf = new ByteArrayOutputStream()
    val interaction =
      new TerminalInteraction(
        new PrintStream(buf),
        useColor = false,
        animated = false
      )
    val tracker = new CostTracker
    flow(
      args = OrcaArgs(),
      interaction = Some(interaction),
      extraListeners = List(tracker)
    ) {
      summon[FlowContext]
        .emit(
          OrcaEvent
            .TokensUsed("test-agent", Some("test-model"), Usage(10L, 5L, None))
        )
    }
    // TerminalInteraction ignores TokensUsed; CostTracker should accumulate.
    assertEquals(tracker.total, Usage(10L, 5L, None))
