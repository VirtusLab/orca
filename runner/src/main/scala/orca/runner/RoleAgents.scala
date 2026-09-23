package orca.runner

import orca.AgentSet
import orca.agents.Agent
import orca.review.ReviewerPrompts
import orca.settings.{AgentSettings, AgentSpec}
import ox.{ResourceScope, tap}

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
    * A foreign role's agent is closed when the enclosing scope ends. Its close
    * is registered AS the role resolves, so an earlier foreign role is still
    * closed when a later override throws. Roles on a wired backend are left to
    * whoever closes the wired agents.
    */
  def resolveAll(
      project: AgentSettings,
      global: AgentSettings,
      overrides: RoleOverrides,
      agents: WiredAgents
  )(using ResourceScope): RoleResolution =
    val planning =
      resolveOne(
        label = "planning",
        costRole = "planning",
        projectSpec = project.planning,
        globalSpec = global.planning,
        overrideSelect = overrides.planning,
        agents = agents
      ).tap(closeIfForeign)
    val coding =
      resolveOne(
        label = "coding",
        costRole = "coding",
        projectSpec = project.coding,
        globalSpec = global.coding,
        overrideSelect = overrides.coding,
        agents = agents
      ).tap(closeIfForeign)
    val review =
      resolveOne(
        label = "review",
        // The reviewers, lint and the picker already bill under this tag; one
        // concept gets one bucket in the by-role block.
        costRole = ReviewerPrompts.Role,
        projectSpec = project.review,
        globalSpec = global.review,
        overrideSelect = overrides.review,
        agents = agents
      ).tap(closeIfForeign)
    val all = List(planning, coding, review)
    RoleResolution(
      roles = ResolvedRoles(planning.agent, coding.agent, review.agent),
      announcement = "agents: " + all.map(announce).mkString(", "),
      foreignWarnings = all.flatMap(foreignWarning)
    )

  private def closeIfForeign(c: RoleChoice)(using ResourceScope): Unit =
    if c.foreign then WiredAgents.closeAfterScope(List(c.agent))

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
    * backend's harness; the built-in default resolves to claude).
    *
    * The model comes from the spec's pin first, and only then from the agent's
    * own configured model (claude/codex/gemini pin a default; pi and opencode
    * don't).
    */
  private def harnessAndModel(
      agent: Agent[?],
      spec: Option[AgentSpec]
  ): (String, Option[String]) =
    val harness =
      AgentSpec.harnessNameFor(spec.fold(agent.backendTag)(_.backend))
    (harness, spec.flatMap(_.model).orElse(agent.config.model.map(_.name)))

  private def resolveOne(
      label: String,
      costRole: String,
      projectSpec: Option[AgentSpec],
      globalSpec: Option[AgentSpec],
      overrideSelect: Option[AgentSet => Agent[?]],
      agents: WiredAgents
  ): RoleChoice =
    val choice = overrideSelect match
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
            val agent = agents.agentFor(spec.backend, spec.model)
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
    choice.copy(agent = tagged(choice.label, costRole, choice.agent))

  /** Stamp the role onto its agent: the role's label as the cost-report `name`,
    * unless the agent was named with `withName`, and `costRole` (for review,
    * the reviewers' own tag) as the `role` axis, unless one is set. Done here
    * rather than in the shipped flows because a user-written flow, the shell,
    * and the runtime's own cheap one-shots (branch naming, default commit
    * messages) never pass through one; `Agent.cheap` carries name and role
    * over, so those sub-calls are covered by tagging the role agent itself.
    */
  private def tagged(
      label: String,
      costRole: String,
      agent: Agent[?]
  ): Agent[?] =
    val named = agent.withDefaultNameReplacedBy(label)
    if named.role.isEmpty then named.withRole(costRole) else named

  /** Stands in for a role where nothing orca can see pins a model — neither the
    * settings nor the wired agent (pi and opencode pin no default). The harness
    * picks one at spawn time, which the header can't know before the first
    * turn.
    */
  private val UnpinnedModelLabel = "<harness default>"

  /** One role's announcement segment, `label=harness:model (source)` — pure
    * formatting of [[RoleChoice]]'s precomputed fields.
    */
  private def announce(c: RoleChoice): String =
    s"${c.label}=${c.harness}:${c.model.getOrElse(UnpinnedModelLabel)} (${sourceLabel(c.source)})"

  private def sourceLabel(source: RoleSource): String =
    source match
      case RoleSource.Override => "override"
      case RoleSource.Project  => "project"
      case RoleSource.Global   => "global"
      case RoleSource.Default  => "default"

  /** An override that escaped the wired set is event-blind — its cost/steps
    * never reach the terminal or cost tracker — so it gets a loud warning; it
    * is still closed (see [[resolveAll]]), so later runs through it are
    * refused.
    */
  private def foreignWarning(c: RoleChoice): Option[String] =
    Option.when(c.foreign)(
      s"warning: ${c.label} agent was not built from this flow's context " +
        "— events may not reach the terminal/cost tracker"
    )
