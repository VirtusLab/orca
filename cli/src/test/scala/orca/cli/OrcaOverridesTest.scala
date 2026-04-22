package orca.cli

import _root_.orca.{
  Backend,
  ClaudeTool,
  CostTracker,
  FlowContext,
  FsTool,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  Usage,
  fs,
  orca
}

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaOverridesTest extends munit.FunSuite:

  private def silentInteraction: TerminalInteraction =
    new TerminalInteraction(
      new PrintStream(new ByteArrayOutputStream()),
      useColor = false,
      animated = false
    )

  test("orca uses a custom FsTool when supplied"):
    val fake = new FsTool:
      def read(path: String): String = "canned content"
      def write(path: String, content: String): Unit = ()
      def list(glob: String): List[String] = List("custom")
    var observed: String = ""
    orca(fs = Some(fake), interaction = silentInteraction) {
      observed = fs.read("ignored")
    }
    assertEquals(observed, "canned content")

  test("orca uses a custom ClaudeTool when supplied"):
    val fakeClaude = new ClaudeTool:
      val name = "fake"
      def haiku = this
      def sonnet = this
      def opus = this
      def withConfig(c: LlmConfig) = this
      def withSystemPrompt(p: String) = this
      def ask(prompt: String, config: LlmConfig = LlmConfig.default): String =
        s"echo: $prompt"
      def result[
          O: sttp.tapir.Schema: com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
      ]: LlmCall[Backend.ClaudeCode.type, O] = ???
    var observed: String = ""
    orca(claude = Some(fakeClaude), interaction = silentInteraction) {
      observed = summon[FlowContext].claude.ask("hi")
    }
    assertEquals(observed, "echo: hi")

  test("orca collects extra listeners alongside the interaction's"):
    val buf = new ByteArrayOutputStream()
    val interaction =
      new TerminalInteraction(
        new PrintStream(buf),
        useColor = false,
        animated = false
      )
    val tracker = new CostTracker
    orca(interaction = interaction, extraListeners = List(tracker)) {
      summon[FlowContext]
        .emit(OrcaEvent.TokensUsed(Usage(10L, 5L, None)))
    }
    // TerminalInteraction ignores TokensUsed; CostTracker should accumulate.
    assertEquals(tracker.total, Usage(10L, 5L, None))
