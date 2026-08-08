package orca.runner

import orca.AgentSet
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  Model,
  OpencodeAgent,
  PiAgent
}
import orca.settings.{AgentSettings, AgentSpec}

/** The three role agents resolved for one run — every field an existentially
  * typed [[Agent]] since planning/coding/review can each land on a different
  * backend.
  */
private[runner] case class ResolvedRoles(
    planning: Agent[?],
    coding: Agent[?],
    review: Agent[?]
)

/** Where a resolved role's agent came from, in precedence order. Drives the
  * role-announcement `Step`'s `(source)` suffix; the winning [[AgentSpec]] (if
  * any) drives the `harness[:model]` part.
  */
private[runner] enum RoleSource:
  case Override, Project, Global, Default

/** The three per-role programmatic overrides passed to `flow(...)` — selector-
  * shaped so a derived sibling of a wired agent stays expressible. Applied
  * against the run's wired [[AgentSet]], they win over both settings files.
  */
private[orca] case class RoleOverrides(
    planning: Option[AgentSet => Agent[?]],
    coding: Option[AgentSet => Agent[?]],
    review: Option[AgentSet => Agent[?]]
)

/** Outcome of [[RoleAgents.resolveAll]]: the resolved role agents, the
  * ready-to-emit announcement `Step` text, and any foreign-agent warnings (one
  * per role whose override escaped the wired set).
  */
private[orca] case class RoleResolution(
    roles: ResolvedRoles,
    announcement: String,
    foreignWarnings: List[String]
)

private[orca] object RoleAgents:
  /** Project-over-global precedence (ADR 0020 §10) for one role's
    * [[AgentSpec]]: the project file's entry wins outright when present, else
    * the global file's, else unset. The ONE place this decision is made —
    * shared by [[resolveOne]] (a full run, which folds a programmatic override
    * in ahead of this) and [[orca.shell.actions.ConfigSummary.agentsLine]] (a
    * static display with no override to apply) — so the summary can never show
    * a different winner than a real run would resolve.
    */
  private[orca] def projectOverGlobal(
      projectSpec: Option[AgentSpec],
      globalSpec: Option[AgentSpec]
  ): Option[AgentSpec] = projectSpec.orElse(globalSpec)

  /** Resolve the three role agents against the run's wired set — every role
    * shares a wired backend, so events and close() behave exactly as for the
    * wired five. An unset role defaults to claude with no model pin.
    */
  def resolve(settings: AgentSettings, agents: WiredAgents): ResolvedRoles =
    ResolvedRoles(
      planning = one(settings.planning, agents),
      coding = one(settings.coding, agents),
      review = one(settings.review, agents)
    )

  /** Resolve all three roles AND everything derived from that resolution, so
    * the override>project>global>default precedence is encoded once. Per role:
    * apply the programmatic override if present (winning over both files), else
    * resolve the project-or-global spec against the wired set, else default to
    * claude. The winning [[RoleSource]] and [[AgentSpec]] are captured as each
    * role resolves and read back by the announcement, so the precedence ladder
    * can't drift between agent selection and the `(source)` labels.
    *
    * A settings-resolved role is always one of the wired agents; only a
    * programmatic override can escape the wired set, so a foreign-agent warning
    * only ever fires for an override.
    *
    * `onRoleResolved` is invoked with each role's agent AS IT resolves, before
    * the next role's override runs — so an EARLIER role that resolved to a
    * foreign agent is still covered by the caller's close guard when a LATER
    * override throws and this method never returns.
    */
  def resolveAll(
      project: AgentSettings,
      global: AgentSettings,
      overrides: RoleOverrides,
      agents: WiredAgents,
      onRoleResolved: Agent[?] => Unit
  ): RoleResolution =
    val planning =
      resolveOne(
        "planning",
        project.planning,
        global.planning,
        overrides.planning,
        agents
      )
    onRoleResolved(planning.agent)
    val coding =
      resolveOne(
        "coding",
        project.coding,
        global.coding,
        overrides.coding,
        agents
      )
    onRoleResolved(coding.agent)
    val review =
      resolveOne(
        "review",
        project.review,
        global.review,
        overrides.review,
        agents
      )
    onRoleResolved(review.agent)
    val all = List(planning, coding, review)
    RoleResolution(
      roles = ResolvedRoles(planning.agent, coding.agent, review.agent),
      announcement = "agents: " + all.map(announce).mkString(", "),
      foreignWarnings = all.flatMap(foreignWarning)
    )

  /** One role's resolved agent plus the provenance the announcement reads.
    * `harness`/`model` are precomputed at resolution time (the only place that
    * knows whether a settings [[AgentSpec]] won or the agent's own defaults
    * apply), so announcing is pure formatting. `foreign` is true only when an
    * override escaped the wired set.
    */
  private case class RoleChoice(
      label: String,
      agent: Agent[?],
      source: RoleSource,
      harness: String,
      model: Option[String],
      foreign: Boolean
  )

  /** The `harness`/`model` pair an announcement shows for one role. The harness
    * comes from the winning [[AgentSpec]] when there is one (project/global),
    * otherwise from the resolved agent's backend tag (an override shows its
    * backend's harness; the built-in default resolves to claude). The model
    * shown is whichever settings never override: a settings pin wins outright
    * (that's what the user asked for); absent a pin, the resolved agent's OWN
    * configured model (e.g. claude's wired Opus1M default) is shown instead of
    * staying silent — pi/opencode, which pin no default, stay bare.
    */
  private def harnessAndModel(
      agent: Agent[?],
      spec: Option[AgentSpec]
  ): (String, Option[String]) =
    spec match
      case Some(s) => (AgentSpec.harnessNameFor(s.backend), s.model)
      case None =>
        val harness =
          agent.backendTag
            .flatMap(AgentSpec.harnessNameFor.get)
            .getOrElse("claude")
        (harness, agent.configuredModel.map(_.name))

  private def resolveOne(
      label: String,
      projectSpec: Option[AgentSpec],
      globalSpec: Option[AgentSpec],
      overrideSelect: Option[AgentSet => Agent[?]],
      agents: WiredAgents
  ): RoleChoice =
    overrideSelect match
      case Some(select) =>
        val agent = select(agents)
        val (harness, model) = harnessAndModel(agent, None)
        RoleChoice(
          label,
          agent,
          RoleSource.Override,
          harness,
          model,
          foreign = !agents.isWiredBackend(agent)
        )
      case None =>
        projectOverGlobal(projectSpec, globalSpec) match
          case Some(spec) =>
            val source =
              if projectSpec.isDefined then RoleSource.Project
              else RoleSource.Global
            val agent = one(Some(spec), agents)
            val (harness, model) = harnessAndModel(agent, Some(spec))
            RoleChoice(label, agent, source, harness, model, foreign = false)
          case None =>
            val (harness, model) = harnessAndModel(agents.claude, None)
            RoleChoice(
              label,
              agents.claude,
              RoleSource.Default,
              harness,
              model,
              foreign = false
            )

  /** One role's announcement segment, `label=harness[:model] (source)` — pure
    * formatting of [[RoleChoice]]'s precomputed fields.
    */
  private def announce(c: RoleChoice): String =
    s"${c.label}=${c.harness}${c.model.map(":" + _).getOrElse("")} (${sourceLabel(c.source)})"

  private def sourceLabel(source: RoleSource): String =
    source match
      case RoleSource.Override => "override"
      case RoleSource.Project  => "project"
      case RoleSource.Global   => "global"
      case RoleSource.Default  => "default"

  /** An override that escaped the wired set is event-blind — its cost/steps
    * never reach the terminal or cost tracker — so it gets a loud warning; the
    * close fan-outs still close it to avoid a resource leak.
    */
  private def foreignWarning(c: RoleChoice): Option[String] =
    Option.when(c.foreign)(
      s"warning: ${c.label} agent was not built from this flow's context " +
        "— events may not reach the terminal/cost tracker"
    )

  /** Resolves `spec` against `agents`: no spec is bare claude, a spec with no
    * model pin is that tag's wired agent as-is (via [[AgentSet.agentFor]] — the
    * single place a [[BackendTag]] maps to one of the five wired agents), a
    * spec with a model pin applies it via that agent's own `withModel`.
    *
    * The tag match below is NOT a second "which agent" decision — `agent` is
    * already the one `agentFor` picked — it exists only because each concrete
    * `*Agent` trait's `withModel` has a different signature (`OpencodeAgent`'s
    * takes a raw `provider/model` string, the rest a [[Model]]), so the cast
    * recovering that concrete type is unavoidable. The cast is safe by
    * construction: `agent` came from `agentFor(tag)` for this SAME `tag`.
    */
  private def one(spec: Option[AgentSpec], agents: WiredAgents): Agent[?] =
    spec match
      case None => agents.claude
      case Some(AgentSpec(tag, model)) =>
        val agent = agents.agentFor(tag)
        model match
          case None => agent
          case Some(m) =>
            tag match
              case BackendTag.ClaudeCode =>
                agent.asInstanceOf[ClaudeAgent].withModel(Model(m))
              case BackendTag.Codex =>
                agent.asInstanceOf[CodexAgent].withModel(Model(m))
              case BackendTag.Opencode =>
                agent.asInstanceOf[OpencodeAgent].withModel(m)
              case BackendTag.Pi =>
                agent.asInstanceOf[PiAgent].withModel(Model(m))
              case BackendTag.Gemini =>
                agent.asInstanceOf[GeminiAgent].withModel(Model(m))
