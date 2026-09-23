package orca.runner

import orca.agents.{
  Agent,
  AgentConfig,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  Model,
  OpencodeAgent,
  PiAgent
}
import orca.review.ReviewerPrompts
import orca.settings.{AgentSettings, AgentSpec}
import orca.testkit.{ScriptedBackend, TestAgent}

/** Pins [[RoleAgents.resolveAll]]'s mapping from settings to the run's
  * [[WiredAgents]]: unset stays claude, a bare spec picks the matching wired
  * backend, and a model pin produces a `withModel` sibling that still shares
  * the wired agent's backend — the same sharing [[LeadAgentIdentityTest]] pins
  * for the `_.claude.opus` selector shape, here exercised through
  * settings-driven resolution instead of a flow selector.
  *
  * Resolution tags each role's agent, so "came from the wired agent" is
  * asserted as a shared backend rather than reference equality.
  */
class RoleAgentsTest extends munit.FunSuite:

  test("unset settings resolve every role to the wired claude"):
    val wired = wiredAgents()
    val roles = resolvedRoles(AgentSettings.empty, wired)
    assert(
      List(roles.planning, roles.coding, roles.review)
        .forall(_.sharesBackendWith(wired.claude))
    )

  test("a bare per-role spec picks the matching wired backend"):
    val wired = wiredAgents()
    val roles = resolvedRoles(
      AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      wired
    )
    assert(
      roles.coding.sharesBackendWith(wired.codex),
      "coding must be the wired codex"
    )
    assert(
      List(roles.planning, roles.review)
        .forall(_.sharesBackendWith(wired.claude)),
      "an unset role still defaults to the wired claude"
    )

  test("a model pin resolves to a sibling on the wired backend"):
    val wired = wiredAgents()
    val settings = AgentSettings(
      planning = Some(AgentSpec(BackendTag.ClaudeCode, Some("claude-opus-x")))
    )
    val roles = resolvedRoles(settings, wired)
    assert(roles.planning.sharesBackendWith(wired.claude))
    assertEquals(roles.planning.config.model, Some(Model("claude-opus-x")))

  test("opencode's model pin keeps the raw provider/model string"):
    val wired = wiredAgents()
    val settings = AgentSettings(
      review = Some(AgentSpec(BackendTag.Opencode, Some("ollama/qwen-coder")))
    )
    val roles = resolvedRoles(settings, wired)
    assert(roles.review.sharesBackendWith(wired.opencode))
    assertEquals(roles.review.config.model, Some(Model("ollama/qwen-coder")))

  test(
    "resolveAll announces the default, project, and global sources per role"
  ):
    val wired = wiredAgents()
    val resolution = resolveInScope(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      global = AgentSettings(review = Some(AgentSpec(BackendTag.Gemini, None))),
      overrides = RoleOverrides(None, None, None),
      agents = wired
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
    val wired =
      wiredAgents(claude =
        stub(BackendTag.ClaudeCode, Some("claude-opus-5-5[1m]"))
      )
    val resolution = resolveInScope(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wired
    )
    assert(
      resolution.announcement.contains(
        "planning=claude:claude-opus-5-5[1m] (default)"
      ),
      s"expected the wired default model in the segment: ${resolution.announcement}"
    )

  test(
    "resolveAll marks a role nobody pinned a model for as the harness's own default"
  ):
    val resolution = resolveInScope(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents()
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
    val resolution = resolveInScope(
      project = AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None))),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(codex = stub(BackendTag.Codex, Some("gpt-6-sol")))
    )
    assert(
      resolution.announcement.contains("coding=codex:gpt-6-sol (project)"),
      s"expected the wired codex's own model: ${resolution.announcement}"
    )

  test("resolveAll renders a project model pin as harness:model"):
    val resolution = resolveInScope(
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
    "a project model pin wins over the wired agent's own configured model"
  ):
    val resolution = resolveInScope(
      project = AgentSettings(planning =
        Some(AgentSpec(BackendTag.ClaudeCode, Some("claude-haiku-4-5")))
      ),
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = wiredAgents(claude =
        stub(BackendTag.ClaudeCode, Some("claude-opus-5-5[1m]"))
      )
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
    val resolution = resolveInScope(
      project =
        AgentSettings(coding = Some(AgentSpec(BackendTag.Gemini, None))),
      global = AgentSettings.empty,
      overrides =
        RoleOverrides(None, Some((a: orca.AgentSet) => a.codex), None),
      agents = wired
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
    val roles = resolvedRoles(AgentSettings.empty, wiredAgents())
    // The review role's cost tag is the reviewers' own, not its label: the
    // reviewers, lint and the picker all bill under it, and the by-role block
    // must not grow a second bucket for the same concept.
    assertEquals(
      List(roles.planning, roles.coding, roles.review)
        .map(a => (a.name, a.role)),
      List(
        ("planning", Some("planning")),
        ("coding", Some("coding")),
        ("review", Some(ReviewerPrompts.Role))
      )
    )

  test("a role the agent already carries is not overwritten"):
    val resolution = resolveInScope(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides = RoleOverrides(
        None,
        Some((a: orca.AgentSet) => a.claude.withRole("x")),
        None
      ),
      agents = wiredAgents()
    )
    assertEquals(resolution.roles.coding.role, Some("x"))

  test("a name set at wiring time is not overwritten"):
    val roles = resolvedRoles(
      AgentSettings.empty,
      wiredAgents(claude = stub(BackendTag.ClaudeCode).withName("bob"))
    )
    assertEquals(
      (roles.coding.name, roles.coding.role),
      ("bob", Some("coding"))
    )

  test("resolveAll warns for an override that escapes the wired set"):
    val foreign = stub(BackendTag.ClaudeCode)
    val resolution = resolveInScope(
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

  test(
    "resolveAll closes a foreign role's agent when the scope ends, but no wired one"
  ):
    val foreignBackend = ScriptedBackend.unused(BackendTag.ClaudeCode)
    val foreign = TestAgent(foreignBackend)
    val wiredCodexBackend = ScriptedBackend.unused(BackendTag.Codex)
    val wiredCodex = TestAgent(wiredCodexBackend)
    val _ = resolveInScope(
      project = AgentSettings.empty,
      global = AgentSettings.empty,
      overrides = RoleOverrides(
        Some((_: orca.AgentSet) => foreign),
        Some((a: orca.AgentSet) => a.codex),
        None
      ),
      agents = wiredAgents(codex = wiredCodex)
    )
    assertEquals(
      (foreignBackend.isClosed, wiredCodexBackend.isClosed),
      (true, false)
    )

  /** [[RoleAgents.resolveAll]] in a scope of its own, so any foreign role's
    * agent is closed on return.
    */
  private def resolveInScope(
      project: AgentSettings,
      global: AgentSettings,
      overrides: RoleOverrides,
      agents: WiredAgents
  ): RoleResolution =
    ox.resourceScope(
      RoleAgents.resolveAll(project, global, overrides, agents)
    )

  /** [[RoleAgents.resolveAll]] with no global settings and no programmatic
    * override — the settings-only path the mapping tests exercise.
    */
  private def resolvedRoles(
      settings: AgentSettings,
      agents: WiredAgents
  ): ResolvedRoles =
    resolveInScope(
      project = settings,
      global = AgentSettings.empty,
      overrides = RoleOverrides(None, None, None),
      agents = agents
    ).roles

  /** An agent on a backend of its own; every turn fails. */
  private def stub[B <: BackendTag & Singleton](
      tag: B,
      model: Option[String] = None
  ): Agent[B] =
    TestAgent(
      ScriptedBackend.unused(tag),
      "main",
      config = AgentConfig(model = model.map(Model(_)))
    )

  private def wiredAgents(
      claude: ClaudeAgent = stub(BackendTag.ClaudeCode),
      codex: CodexAgent = stub(BackendTag.Codex),
      opencode: OpencodeAgent = stub(BackendTag.Opencode),
      pi: PiAgent = stub(BackendTag.Pi),
      gemini: GeminiAgent = stub(BackendTag.Gemini)
  ): WiredAgents =
    new WiredAgents(claude, codex, opencode, pi, gemini)
