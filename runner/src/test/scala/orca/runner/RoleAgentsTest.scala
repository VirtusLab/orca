package orca.runner

import orca.agents.{
  AgentCall,
  AgentConfig,
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  JsonData,
  Model,
  OpencodeAgent,
  PiAgent,
  ToolSet
}
import orca.settings.{AgentSettings, AgentSpec}

/** Pins [[RoleAgents.resolve]]'s pure mapping from settings to the run's
  * [[WiredAgents]]: unset stays claude, a bare spec picks the matching wired
  * backend by reference, and a model pin produces a `withModel` sibling that
  * still shares the wired backend's identity — the same sharing
  * [[LeadAgentIdentityTest]] pins for the `_.claude.opus` selector shape, here
  * exercised through settings-driven resolution instead of a flow selector.
  */
class RoleAgentsTest extends munit.FunSuite:

  test("unset settings resolve every role to the wired claude, unchanged"):
    val wired = wiredAgents()
    val resolved = RoleAgents.resolve(AgentSettings.empty, wired)
    assert(
      resolved.planning.eq(wired.claude),
      "planning must be the wired claude"
    )
    assert(resolved.coding.eq(wired.claude), "coding must be the wired claude")
    assert(resolved.review.eq(wired.claude), "review must be the wired claude")

  test("a bare per-role spec picks the matching wired backend by reference"):
    val wired = wiredAgents()
    val settings = AgentSettings(
      coding = Some(AgentSpec(BackendTag.Codex, None))
    )
    val resolved = RoleAgents.resolve(settings, wired)
    assert(resolved.coding.eq(wired.codex), "coding must be the wired codex")
    assert(
      resolved.planning.eq(wired.claude),
      "an unset role still defaults to the wired claude"
    )
    assert(
      resolved.review.eq(wired.claude),
      "an unset role still defaults to the wired claude"
    )

  test(
    "a model pin resolves to a withModel sibling that shares the wired " +
      "backend's identity"
  ):
    val token = new AnyRef
    val wiredClaude = new RecordingModelClaude(token)
    val wired = wiredAgents(claude = wiredClaude)
    val settings = AgentSettings(
      planning = Some(AgentSpec(BackendTag.ClaudeCode, Some("claude-opus-x")))
    )
    val resolved = RoleAgents.resolve(settings, wired)
    assert(
      !resolved.planning.eq(wiredClaude),
      "a model pin must produce a new sibling instance, not the wired agent " +
        "itself"
    )
    assertEquals(
      resolved.planning.backendIdentity,
      Some(token),
      "the sibling must still share the wired backend's identity"
    )
    resolved.planning match
      case sibling: RecordingModelClaude =>
        assertEquals(sibling.pinnedModel, Some(Model("claude-opus-x")))
      case other =>
        fail(s"expected a RecordingModelClaude sibling, got $other")

  test(
    "opencode's model pin passes the raw provider/model string to withModel"
  ):
    val token = new AnyRef
    val wiredOpencode = new RecordingOpencode(token)
    val wired = wiredAgents(opencode = wiredOpencode)
    val settings = AgentSettings(
      review = Some(AgentSpec(BackendTag.Opencode, Some("ollama/qwen-coder")))
    )
    val resolved = RoleAgents.resolve(settings, wired)
    assert(
      !resolved.review.eq(wiredOpencode),
      "a model pin must produce a new sibling instance, not the wired agent " +
        "itself"
    )
    assertEquals(resolved.review.backendIdentity, Some(token))
    resolved.review match
      case sibling: RecordingOpencode =>
        assertEquals(sibling.pinnedModel, Some("ollama/qwen-coder"))
      case other =>
        fail(s"expected a RecordingOpencode sibling, got $other")

  test(
    "resolveAll announces the default, project, and global sources per role"
  ):
    val wired = wiredAgents()
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      global = AgentSettings(review = Some(AgentSpec(BackendTag.Gemini, None))),
      overrides = RoleOverrides(None, None, None),
      agents = wired
    )
    assertEquals(
      resolution.announcement,
      "agents: planning=claude (default), coding=codex (project), " +
        "review=gemini (global)"
    )
    assertEquals(resolution.foreignWarnings, Nil)

  test("resolveAll renders a project model pin as harness:model"):
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(coding =
        Some(AgentSpec(BackendTag.Codex, Some("gpt-5-mini")))
      ),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents()
    )
    assert(
      resolution.announcement.contains("coding=codex:gpt-5-mini (project)"),
      s"expected the pinned model in the segment: ${resolution.announcement}"
    )

  test(
    "resolveAll marks an override's source as (override) with its backend harness"
  ):
    val wired = wiredAgents()
    val resolution = RoleAgents.resolveAll(
      project =
        AgentSettings(coding = Some(AgentSpec(BackendTag.Gemini, None))),
      global = AgentSettings.empty,
      overrides =
        RoleOverrides(None, Some((a: orca.AgentSet) => a.codex), None),
      agents = wired
    )
    assert(
      resolution.announcement.contains("coding=codex (override)"),
      s"an override must beat the project file and label (override): " +
        resolution.announcement
    )
    assert(
      resolution.foreignWarnings.isEmpty,
      "a wired override is not foreign"
    )

  test("resolveAll warns for an override that escapes the wired set"):
    val foreign = new RecordingModelClaude(new AnyRef)
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides =
        RoleOverrides(None, Some((_: orca.AgentSet) => foreign), None),
      agents = wiredAgents()
    )
    assert(
      resolution.foreignWarnings.exists(
        _.contains("coding agent was not built from this flow's context")
      ),
      s"expected a foreign-agent warning: ${resolution.foreignWarnings}"
    )

  private def wiredAgents(
      claude: ClaudeAgent = StubAgent.claude,
      codex: CodexAgent = NoopCodex,
      opencode: OpencodeAgent = NoopOpencode,
      pi: PiAgent = NoopPi,
      gemini: GeminiAgent = NoopGemini
  ): WiredAgents =
    new WiredAgents(claude, codex, opencode, pi, gemini)

  /** A `ClaudeAgent` whose `withModel` returns a NEW instance sharing `token`
    * as its `backendIdentity` (mirroring how a real backend's `withModel`
    * sibling shares the underlying `AgentBackend`) and records the pinned model
    * — the seam [[StubClaudeAgent]]'s no-op `withModel` (which returns `this`)
    * can't exercise.
    */
  private class RecordingModelClaude(
      token: AnyRef,
      val pinnedModel: Option[Model] = None
  ) extends StubClaudeAgent("recording-model-claude"):
    override private[orca] def backendIdentity: Option[AnyRef] = Some(token)
    override def withModel(model: Model): ClaudeAgent =
      new RecordingModelClaude(token, Some(model))

  /** The `OpencodeAgent` sibling of [[RecordingModelClaude]] — `withModel`
    * takes the raw `provider/model` string rather than a [[Model]], so it needs
    * its own recording stub to pin that the resolved model string flows through
    * unwrapped.
    */
  private class RecordingOpencode(
      token: AnyRef,
      val pinnedModel: Option[String] = None
  ) extends OpencodeAgent:
    val name = "recording-opencode"
    def anthropicOpus: OpencodeAgent = this
    def anthropicSonnet: OpencodeAgent = this
    def anthropicHaiku: OpencodeAgent = this
    def openaiSol: OpencodeAgent = this
    def openaiTerra: OpencodeAgent = this
    def openaiLuna: OpencodeAgent = this
    override private[orca] def backendIdentity: Option[AnyRef] = Some(token)
    def withModel(providerModel: String): OpencodeAgent =
      new RecordingOpencode(token, Some(providerModel))
    def withConfig(config: AgentConfig): OpencodeAgent = this
    def withSystemPrompt(prompt: String): OpencodeAgent = this
    def withName(name: String): OpencodeAgent = this
    def withTools(tools: ToolSet): OpencodeAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Opencode.type, O] =
      throw new UnsupportedOperationException

  private object NoopCodex extends CodexAgent:
    val name = "noop-codex"
    // A real backend tag so the override-announcement test can read the
    // resolved backend's harness (`codex`) off the agent, as production does.
    override private[orca] def backendTag: Option[BackendTag] =
      Some(BackendTag.Codex)
    def mini: CodexAgent = this
    def withModel(model: Model): CodexAgent = this
    def withConfig(config: AgentConfig): CodexAgent = this
    def withSystemPrompt(prompt: String): CodexAgent = this
    def withName(name: String): CodexAgent = this
    def withTools(tools: ToolSet): CodexAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Codex.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
      throw new UnsupportedOperationException

  private object NoopOpencode extends OpencodeAgent:
    val name = "noop-opencode"
    def anthropicOpus: OpencodeAgent = this
    def anthropicSonnet: OpencodeAgent = this
    def anthropicHaiku: OpencodeAgent = this
    def openaiSol: OpencodeAgent = this
    def openaiTerra: OpencodeAgent = this
    def openaiLuna: OpencodeAgent = this
    def withModel(providerModel: String): OpencodeAgent = this
    def withConfig(config: AgentConfig): OpencodeAgent = this
    def withSystemPrompt(prompt: String): OpencodeAgent = this
    def withName(name: String): OpencodeAgent = this
    def withTools(tools: ToolSet): OpencodeAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Opencode.type, O] =
      throw new UnsupportedOperationException

  private object NoopPi extends PiAgent:
    val name = "noop-pi"
    def withModel(model: Model): PiAgent = this
    def withConfig(config: AgentConfig): PiAgent = this
    def withSystemPrompt(prompt: String): PiAgent = this
    def withName(name: String): PiAgent = this
    def withTools(tools: ToolSet): PiAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] =
      throw new UnsupportedOperationException

  private object NoopGemini extends GeminiAgent:
    val name = "noop-gemini"
    def flash: GeminiAgent = this
    def withModel(model: Model): GeminiAgent = this
    def withConfig(config: AgentConfig): GeminiAgent = this
    def withSystemPrompt(prompt: String): GeminiAgent = this
    def withName(name: String): GeminiAgent = this
    def withTools(tools: ToolSet): GeminiAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Gemini.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Gemini.type, O] =
      throw new UnsupportedOperationException
