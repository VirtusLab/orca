package orca.llm

/** Verifies that `LlmTool.cheap` returns the expected cheap variant per
  * backend, and that the default implementation returns `this` (for backends
  * with no cheaper tier, e.g. `PiTool`).
  */
class LlmCheapTest extends munit.FunSuite:

  // ── per-backend cheap assertions ────────────────────────────────────────

  test("ClaudeTool.cheap returns haiku"):
    val tool = new StubClaudeTool
    val c = tool.cheap
    assertEquals(c.name, "haiku")

  test("CodexTool.cheap returns mini"):
    val tool = new StubCodexTool
    val c = tool.cheap
    assertEquals(c.name, "mini")

  test("GeminiTool.cheap returns flash"):
    val tool = new StubGeminiTool
    val c = tool.cheap
    assertEquals(c.name, "flash")

  test("OpencodeTool.cheap returns anthropicHaiku"):
    val tool = new StubOpencodeTool
    val c = tool.cheap
    assertEquals(c.name, "anthropicHaiku")

  test("PiTool.cheap returns this (no cheaper tier)"):
    val tool = new StubPiTool
    assertSameInstance(tool.cheap, tool)

  // ── LlmTool base default: cheap returns this ─────────────────────────────

  test("LlmTool default cheap returns this"):
    val tool = new StubBaseLlm
    assertSameInstance(tool.cheap, tool)

  private def assertSameInstance(a: Any, b: Any): Unit =
    assert(
      a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef],
      s"expected same instance but got $a vs $b"
    )

  // ── minimal stubs ────────────────────────────────────────────────────────

  private class StubBaseLlm extends LlmTool[BackendTag.Pi.type]:
    val name: String = "base"
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] = ???
    def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Pi.type, O] = ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.Pi.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.Pi.type] = this
    def withName(n: String): LlmTool[BackendTag.Pi.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.Pi.type] = this

  private class StubClaudeTool extends ClaudeTool:
    val name: String = "claude"
    def haiku: ClaudeTool = namedClaude("haiku")
    def sonnet: ClaudeTool = namedClaude("sonnet")
    def opus: ClaudeTool = namedClaude("opus")
    def fable: ClaudeTool = namedClaude("fable")
    def withNetworkTools(t: Seq[String]): ClaudeTool = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
    def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
    private def namedClaude(n: String): ClaudeTool =
      val self = this
      new ClaudeTool:
        val name: String = n
        def haiku: ClaudeTool = self.haiku
        def sonnet: ClaudeTool = self.sonnet
        def opus: ClaudeTool = self.opus
        def fable: ClaudeTool = self.fable
        def withNetworkTools(t: Seq[String]): ClaudeTool = self
        def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
        def resultAs[O: JsonData: Announce]
            : LlmCall[BackendTag.ClaudeCode.type, O] = ???
        def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
        def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] =
          this
        def withName(n2: String): LlmTool[BackendTag.ClaudeCode.type] = this
        def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this

  private class StubCodexTool extends CodexTool:
    val name: String = "codex"
    def mini: CodexTool = namedCodex("mini")
    def autonomous: AutonomousTextCall[BackendTag.Codex.type] = ???
    def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Codex.type, O] = ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.Codex.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.Codex.type] = this
    def withName(n: String): LlmTool[BackendTag.Codex.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.Codex.type] = this
    private def namedCodex(n: String): CodexTool =
      new CodexTool:
        val name: String = n
        def mini: CodexTool = this
        def autonomous: AutonomousTextCall[BackendTag.Codex.type] = ???
        def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Codex.type, O] =
          ???
        def withConfig(c: LlmConfig): LlmTool[BackendTag.Codex.type] = this
        def withSystemPrompt(p: String): LlmTool[BackendTag.Codex.type] = this
        def withName(n2: String): LlmTool[BackendTag.Codex.type] = this
        def withTools(t: ToolSet): LlmTool[BackendTag.Codex.type] = this

  private class StubGeminiTool extends GeminiTool:
    val name: String = "gemini"
    def flash: GeminiTool = namedGemini("flash")
    def autonomous: AutonomousTextCall[BackendTag.Gemini.type] = ???
    def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Gemini.type, O] =
      ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.Gemini.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.Gemini.type] = this
    def withName(n: String): LlmTool[BackendTag.Gemini.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.Gemini.type] = this
    private def namedGemini(n: String): GeminiTool =
      new GeminiTool:
        val name: String = n
        def flash: GeminiTool = this
        def autonomous: AutonomousTextCall[BackendTag.Gemini.type] = ???
        def resultAs[O: JsonData: Announce]
            : LlmCall[BackendTag.Gemini.type, O] =
          ???
        def withConfig(c: LlmConfig): LlmTool[BackendTag.Gemini.type] = this
        def withSystemPrompt(p: String): LlmTool[BackendTag.Gemini.type] = this
        def withName(n2: String): LlmTool[BackendTag.Gemini.type] = this
        def withTools(t: ToolSet): LlmTool[BackendTag.Gemini.type] = this

  private class StubOpencodeTool extends OpencodeTool:
    val name: String = "opencode"
    def anthropicOpus: OpencodeTool = namedOpencode("anthropicOpus")
    def anthropicSonnet: OpencodeTool = namedOpencode("anthropicSonnet")
    def anthropicHaiku: OpencodeTool = namedOpencode("anthropicHaiku")
    def openaiGpt5: OpencodeTool = namedOpencode("openaiGpt5")
    def openaiGpt5Codex: OpencodeTool = namedOpencode("openaiGpt5Codex")
    def openaiGpt5Mini: OpencodeTool = namedOpencode("openaiGpt5Mini")
    def withModel(providerModel: String): OpencodeTool = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] = ???
    def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Opencode.type, O] =
      ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.Opencode.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.Opencode.type] = this
    def withName(n: String): LlmTool[BackendTag.Opencode.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.Opencode.type] = this
    private def namedOpencode(n: String): OpencodeTool =
      new OpencodeTool:
        val name: String = n
        def anthropicOpus: OpencodeTool = this
        def anthropicSonnet: OpencodeTool = this
        def anthropicHaiku: OpencodeTool = this
        def openaiGpt5: OpencodeTool = this
        def openaiGpt5Codex: OpencodeTool = this
        def openaiGpt5Mini: OpencodeTool = this
        def withModel(pm: String): OpencodeTool = this
        def autonomous: AutonomousTextCall[BackendTag.Opencode.type] = ???
        def resultAs[O: JsonData: Announce]
            : LlmCall[BackendTag.Opencode.type, O] = ???
        def withConfig(c: LlmConfig): LlmTool[BackendTag.Opencode.type] = this
        def withSystemPrompt(p: String): LlmTool[BackendTag.Opencode.type] =
          this
        def withName(n2: String): LlmTool[BackendTag.Opencode.type] = this
        def withTools(t: ToolSet): LlmTool[BackendTag.Opencode.type] = this

  private class StubPiTool extends PiTool:
    val name: String = "pi"
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] = ???
    def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.Pi.type, O] = ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.Pi.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.Pi.type] = this
    def withName(n: String): LlmTool[BackendTag.Pi.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.Pi.type] = this
