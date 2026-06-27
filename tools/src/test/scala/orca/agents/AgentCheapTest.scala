package orca.agents

/** Verifies that `Agent.cheap` returns the expected cheap variant per backend,
  * and that the default implementation returns `this` (for backends with no
  * cheaper tier, e.g. `PiAgent`).
  */
class AgentCheapTest extends munit.FunSuite:

  // ── per-backend cheap assertions ────────────────────────────────────────

  test("ClaudeAgent.cheap returns haiku"):
    val tool = new StubClaudeAgent
    val c = tool.cheap
    assertEquals(c.name, "haiku")

  test("CodexAgent.cheap returns mini"):
    val tool = new StubCodexAgent
    val c = tool.cheap
    assertEquals(c.name, "mini")

  test("GeminiAgent.cheap returns flash"):
    val tool = new StubGeminiAgent
    val c = tool.cheap
    assertEquals(c.name, "flash")

  test("OpencodeAgent.cheap returns anthropicHaiku"):
    val tool = new StubOpencodeAgent
    val c = tool.cheap
    assertEquals(c.name, "anthropicHaiku")

  test("PiAgent.cheap returns this (no cheaper tier)"):
    val tool = new StubPiAgent
    assertSameInstance(tool.cheap, tool)

  // ── Agent base default: cheap returns this ─────────────────────────────

  test("Agent default cheap returns this"):
    val tool = new StubBaseAgent
    assertSameInstance(tool.cheap, tool)

  private def assertSameInstance(a: Any, b: Any): Unit =
    assert(
      a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef],
      s"expected same instance but got $a vs $b"
    )

  // ── minimal stubs ────────────────────────────────────────────────────────

  private class StubBaseAgent extends Agent[BackendTag.Pi.type]:
    val name: String = "base"
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] = ???
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] = ???
    def withConfig(c: AgentConfig): Agent[BackendTag.Pi.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.Pi.type] = this
    def withName(n: String): Agent[BackendTag.Pi.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.Pi.type] = this

  private class StubClaudeAgent extends ClaudeAgent:
    val name: String = "claude"
    def haiku: ClaudeAgent = namedClaude("haiku")
    def sonnet: ClaudeAgent = namedClaude("sonnet")
    def opus: ClaudeAgent = namedClaude("opus")
    def fable: ClaudeAgent = namedClaude("fable")
    def withModel(model: Model): ClaudeAgent = this
    def withNetworkTools(t: Seq[String]): ClaudeAgent = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      ???
    def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
    def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
    private def namedClaude(n: String): ClaudeAgent =
      val self = this
      new ClaudeAgent:
        val name: String = n
        def haiku: ClaudeAgent = self.haiku
        def sonnet: ClaudeAgent = self.sonnet
        def opus: ClaudeAgent = self.opus
        def fable: ClaudeAgent = self.fable
        def withModel(model: Model): ClaudeAgent = self
        def withNetworkTools(t: Seq[String]): ClaudeAgent = self
        def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
        def resultAs[O: JsonData: Announce]
            : AgentCall[BackendTag.ClaudeCode.type, O] = ???
        def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
        def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] =
          this
        def withName(n2: String): Agent[BackendTag.ClaudeCode.type] = this
        def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this

  private class StubCodexAgent extends CodexAgent:
    val name: String = "codex"
    def mini: CodexAgent = namedCodex("mini")
    def withModel(model: Model): CodexAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Codex.type] = ???
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
      ???
    def withConfig(c: AgentConfig): Agent[BackendTag.Codex.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.Codex.type] = this
    def withName(n: String): Agent[BackendTag.Codex.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.Codex.type] = this
    private def namedCodex(n: String): CodexAgent =
      new CodexAgent:
        val name: String = n
        def mini: CodexAgent = this
        def withModel(model: Model): CodexAgent = this
        def autonomous: AutonomousTextCall[BackendTag.Codex.type] = ???
        def resultAs[O: JsonData: Announce]
            : AgentCall[BackendTag.Codex.type, O] =
          ???
        def withConfig(c: AgentConfig): Agent[BackendTag.Codex.type] = this
        def withSystemPrompt(p: String): Agent[BackendTag.Codex.type] = this
        def withName(n2: String): Agent[BackendTag.Codex.type] = this
        def withTools(t: ToolSet): Agent[BackendTag.Codex.type] = this

  private class StubGeminiAgent extends GeminiAgent:
    val name: String = "gemini"
    def flash: GeminiAgent = namedGemini("flash")
    def withModel(model: Model): GeminiAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Gemini.type] = ???
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Gemini.type, O] =
      ???
    def withConfig(c: AgentConfig): Agent[BackendTag.Gemini.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.Gemini.type] = this
    def withName(n: String): Agent[BackendTag.Gemini.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.Gemini.type] = this
    private def namedGemini(n: String): GeminiAgent =
      new GeminiAgent:
        val name: String = n
        def flash: GeminiAgent = this
        def withModel(model: Model): GeminiAgent = this
        def autonomous: AutonomousTextCall[BackendTag.Gemini.type] = ???
        def resultAs[O: JsonData: Announce]
            : AgentCall[BackendTag.Gemini.type, O] =
          ???
        def withConfig(c: AgentConfig): Agent[BackendTag.Gemini.type] = this
        def withSystemPrompt(p: String): Agent[BackendTag.Gemini.type] = this
        def withName(n2: String): Agent[BackendTag.Gemini.type] = this
        def withTools(t: ToolSet): Agent[BackendTag.Gemini.type] = this

  private class StubOpencodeAgent extends OpencodeAgent:
    val name: String = "opencode"
    def anthropicOpus: OpencodeAgent = namedOpencode("anthropicOpus")
    def anthropicSonnet: OpencodeAgent = namedOpencode("anthropicSonnet")
    def anthropicHaiku: OpencodeAgent = namedOpencode("anthropicHaiku")
    def openaiGpt5: OpencodeAgent = namedOpencode("openaiGpt5")
    def openaiGpt5Codex: OpencodeAgent = namedOpencode("openaiGpt5Codex")
    def openaiGpt5Mini: OpencodeAgent = namedOpencode("openaiGpt5Mini")
    def withModel(providerModel: String): OpencodeAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] = ???
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Opencode.type, O] =
      ???
    def withConfig(c: AgentConfig): Agent[BackendTag.Opencode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.Opencode.type] = this
    def withName(n: String): Agent[BackendTag.Opencode.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.Opencode.type] = this
    private def namedOpencode(n: String): OpencodeAgent =
      new OpencodeAgent:
        val name: String = n
        def anthropicOpus: OpencodeAgent = this
        def anthropicSonnet: OpencodeAgent = this
        def anthropicHaiku: OpencodeAgent = this
        def openaiGpt5: OpencodeAgent = this
        def openaiGpt5Codex: OpencodeAgent = this
        def openaiGpt5Mini: OpencodeAgent = this
        def withModel(pm: String): OpencodeAgent = this
        def autonomous: AutonomousTextCall[BackendTag.Opencode.type] = ???
        def resultAs[O: JsonData: Announce]
            : AgentCall[BackendTag.Opencode.type, O] = ???
        def withConfig(c: AgentConfig): Agent[BackendTag.Opencode.type] = this
        def withSystemPrompt(p: String): Agent[BackendTag.Opencode.type] =
          this
        def withName(n2: String): Agent[BackendTag.Opencode.type] = this
        def withTools(t: ToolSet): Agent[BackendTag.Opencode.type] = this

  private class StubPiAgent extends PiAgent:
    val name: String = "pi"
    def withModel(model: Model): PiAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] = ???
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] = ???
    def withConfig(c: AgentConfig): Agent[BackendTag.Pi.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.Pi.type] = this
    def withName(n: String): Agent[BackendTag.Pi.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.Pi.type] = this
