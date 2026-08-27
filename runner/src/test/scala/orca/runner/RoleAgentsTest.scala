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
      agents = wired,
      onRoleResolved = _ => ()
    )
    assertEquals(
      resolution.announcement,
      "agents: planning=claude:<harness default> (default), " +
        "coding=codex:<harness default> (project), " +
        "review=gemini:<harness default> (global)"
    )
    assertEquals(resolution.foreignWarnings, Nil)

  test(
    "resolveAll shows the wired agent's own configured model when no settings pin it"
  ):
    val wired = wiredAgents(claude = new DefaultModelClaude)
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wired,
      onRoleResolved = _ => ()
    )
    assert(
      resolution.announcement.contains(
        "planning=claude:claude-opus-5[1m] (default)"
      ),
      s"expected the wired default model in the segment: ${resolution.announcement}"
    )

  test(
    "resolveAll marks a role nobody pinned a model for as the harness's own default"
  ):
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(),
      onRoleResolved = _ => ()
    )
    assert(
      resolution.announcement.contains(
        "coding=codex:<harness default> (project)"
      ),
      s"a role nobody pinned a model for must say the harness picks: " +
        resolution.announcement
    )

  test(
    "a settings entry naming only a harness announces that agent's own model"
  ):
    // The header and the cost table must name the same model.
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(codex = new DefaultModelCodex),
      onRoleResolved = _ => ()
    )
    assert(
      resolution.announcement.contains("coding=codex:gpt-5.6-sol (project)"),
      s"expected the wired codex's own model: ${resolution.announcement}"
    )

  test("resolveAll renders a project model pin as harness:model"):
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(coding =
        Some(AgentSpec(BackendTag.Codex, Some("gpt-5-mini")))
      ),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(),
      onRoleResolved = _ => ()
    )
    assert(
      resolution.announcement.contains("coding=codex:gpt-5-mini (project)"),
      s"expected the pinned model in the segment: ${resolution.announcement}"
    )

  test(
    "a project model pin wins over the wired agent's own configured model"
  ):
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(planning =
        Some(AgentSpec(BackendTag.ClaudeCode, Some("claude-haiku-4-5")))
      ),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(claude = new DefaultModelClaude),
      onRoleResolved = _ => ()
    )
    assert(
      resolution.announcement.contains(
        "planning=claude:claude-haiku-4-5 (project)"
      ),
      s"the settings pin must not be shadowed by the wired default: " +
        resolution.announcement
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
      agents = wired,
      onRoleResolved = _ => ()
    )
    assert(
      resolution.announcement.contains(
        "coding=codex:<harness default> (override)"
      ),
      s"an override must beat the project file and label (override): " +
        resolution.announcement
    )
    assert(
      resolution.foreignWarnings.isEmpty,
      "a wired override is not foreign"
    )

  test("resolveAll tags each role's agent with its label, for the cost report"):
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(claude = new TaggableClaude("claude")),
      onRoleResolved = _ => ()
    )
    val roles = resolution.roles
    assertEquals(
      List(roles.planning, roles.coding, roles.review)
        .map(a => (a.name, a.role)),
      List(
        ("planning", Some("planning")),
        ("coding", Some("coding")),
        ("review", Some("review"))
      )
    )

  test("a name a programmatic override set deliberately is not overwritten"):
    // The name is what sessions and selectors key off, so an override that
    // picked one keeps it.
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides = RoleOverrides(
        None,
        Some((a: orca.AgentSet) => a.claude.withName("bob")),
        None
      ),
      agents = wiredAgents(claude = new TaggableClaude("claude")),
      onRoleResolved = _ => ()
    )
    assertEquals(resolution.roles.coding.name, "bob")

  test("a pi-backed role is tagged too, though pi's wired default is not main"):
    // The "still carries its backend's own default name" check reads that name
    // off the wired agent, so a backend naming its default something else is
    // covered without a list of names to keep in step.
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Pi, None))),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(),
      onRoleResolved = _ => ()
    )
    assertEquals(resolution.roles.coding.name, "coding")

  test("resolveAll warns for an override that escapes the wired set"):
    val foreign = new RecordingModelClaude(new AnyRef)
    val resolution = RoleAgents.resolveAll(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides =
        RoleOverrides(None, Some((_: orca.AgentSet) => foreign), None),
      agents = wiredAgents(),
      onRoleResolved = _ => ()
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

  /** A `ClaudeAgent` stub that reports a real backend tag and actually applies
    * `withName`/`withRole` — [[StubClaudeAgent]]'s builders return `this`, so
    * the cost-report tagging is invisible through them.
    */
  private class TaggableClaude(
      agentName: String,
      roleTag: Option[String] = None
  ) extends StubClaudeAgent(agentName):
    override def role: Option[String] = roleTag
    override private[orca] def backendTag: Option[BackendTag] =
      Some(BackendTag.ClaudeCode)
    override def withName(name: String): ClaudeAgent =
      new TaggableClaude(name, roleTag)
    override def withRole(role: String): ClaudeAgent =
      new TaggableClaude(agentName, Some(role))

  /** A `ClaudeAgent` stub whose `configuredModel` mirrors the real wired
    * default (claude's Opus1M pin) — [[StubClaudeAgent]]'s bare default has
    * none, so the announcement's "show the wired default model" path needs this
    * to be exercised.
    */
  private class DefaultModelClaude extends StubClaudeAgent("claude"):
    override private[orca] def configuredModel: Option[Model] =
      Some(Model("claude-opus-5[1m]"))

  private class NoopCodexAgent extends CodexAgent:
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

  private object NoopCodex extends NoopCodexAgent

  /** [[NoopCodexAgent]] with a model of its own, for the settings path. */
  private class DefaultModelCodex extends NoopCodexAgent:
    override val name = "codex"
    override private[orca] def configuredModel: Option[Model] =
      Some(Model("gpt-5.6-sol"))

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

  /** Named like the real wired pi (`pi`, not `main`) and honouring `withName`,
    * so a role landing on pi exercises the tagging check against a backend
    * whose default name differs from the others'.
    */
  private class NoopPiAgent(val name: String) extends PiAgent:
    override private[orca] def backendTag: Option[BackendTag] =
      Some(BackendTag.Pi)
    def withModel(model: Model): PiAgent = this
    def withConfig(config: AgentConfig): PiAgent = this
    def withSystemPrompt(prompt: String): PiAgent = this
    def withName(newName: String): PiAgent = new NoopPiAgent(newName)
    def withTools(tools: ToolSet): PiAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] =
      throw new UnsupportedOperationException

  private object NoopPi extends NoopPiAgent("pi")

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
