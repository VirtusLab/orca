package orca.runner

import orca.AgentSet
import orca.agents.{Agent, BackendTag, Model}
import orca.settings.{AgentSettings, AgentSpec}

/** The three role agents resolved for one run — every field an existentially
  * typed [[Agent]] since planning/coding/review can each land on a different
  * backend. `runFlow` opens the existentials with type-variable patterns to
  * construct the concretely-typed [[orca.runner.DefaultFlowContext]].
  */
private[orca] case class ResolvedRoles(
    planning: Agent[?],
    coding: Agent[?],
    review: Agent[?]
)

/** Where a resolved role's agent came from, in precedence order (design
  * decision 10). Drives the role-announcement `Step`'s `(source)` suffix; the
  * winning [[AgentSpec]] (if any) drives the `harness[:model]` part.
  */
private[orca] enum RoleSource:
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
  * per role whose override escaped the wired set). `runFlow` emits the warnings
  * and the announcement, then threads the roles into the existential-opening
  * match and the close guard.
  */
private[orca] case class RoleResolution(
    roles: ResolvedRoles,
    announcement: String,
    foreignWarnings: List[String]
)

private[orca] object RoleAgents:
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

  /** Resolve all three roles AND everything derived from that resolution, in
    * one place, so the override>project>global>default precedence is encoded
    * once (design decision 10). Per role: apply the programmatic override if
    * present (winning over both files), else resolve the project-or-global spec
    * against the wired set, else default to claude. The winning [[RoleSource]]
    * and [[AgentSpec]] are captured as each role resolves and read back by the
    * announcement, rather than re-derived — so the precedence ladder can't
    * drift between agent selection and the `(source)` labels.
    *
    * A settings-resolved role is always one of the wired agents; only a
    * programmatic override can escape the wired set (`_ => myPrebuiltAgent`,
    * built from a separate `AgentWiring`), so a foreign-agent warning only ever
    * fires for an override — event-blind, it never reaches this run's
    * dispatcher.
    *
    * `onRoleResolved` is invoked with each role's agent AS IT resolves, before
    * the next role's override runs. `runFlow` uses it to append to the
    * pre-transfer close guard incrementally, so an EARLIER role that resolved
    * to a foreign agent is still covered when a LATER override throws and this
    * method never returns — resolution is atomic in its RESULT (a throw yields
    * no `RoleResolution`), but the guard must see every agent already built.
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
    * `spec` is the winning project/global [[AgentSpec]] (the model-pin source),
    * absent for an override or the built-in default; `foreign` is true only
    * when an override escaped the wired set.
    */
  private case class RoleChoice(
      label: String,
      agent: Agent[?],
      source: RoleSource,
      spec: Option[AgentSpec],
      foreign: Boolean
  )

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
        RoleChoice(
          label,
          agent,
          RoleSource.Override,
          spec = None,
          foreign = !agents.isWiredBackend(agent)
        )
      case None =>
        projectSpec
          .map((RoleSource.Project, _))
          .orElse(globalSpec.map((RoleSource.Global, _))) match
          case Some((source, spec)) =>
            RoleChoice(
              label,
              one(Some(spec), agents),
              source,
              Some(spec),
              foreign = false
            )
          case None =>
            RoleChoice(
              label,
              agents.claude,
              RoleSource.Default,
              spec = None,
              foreign = false
            )

  /** One role's announcement segment, `label=harness[:model] (source)` (design
    * decision 10). The harness/model come from the winning [[AgentSpec]] when
    * there is one (project/global); otherwise from the resolved backend's tag
    * (an override shows its backend's harness, no model — the override's pin
    * isn't a spec — and the built-in default resolves to claude). The
    * `(source)` label is driven purely by the [[RoleSource]].
    */
  private def announce(c: RoleChoice): String =
    val harness = c.spec match
      case Some(spec) => AgentSpec.harnessNameFor(spec.backend)
      case None =>
        c.agent.backendTag
          .flatMap(AgentSpec.harnessNameFor.get)
          .getOrElse("claude")
    val model = c.spec.flatMap(_.model)
    s"${c.label}=$harness${model.map(":" + _).getOrElse("")} (${sourceLabel(c.source)})"

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

  private def one(spec: Option[AgentSpec], agents: WiredAgents): Agent[?] =
    spec match
      case None => agents.claude
      case Some(AgentSpec(tag, model)) =>
        tag match
          case BackendTag.ClaudeCode =>
            model.fold(agents.claude)(m => agents.claude.withModel(Model(m)))
          case BackendTag.Codex =>
            model.fold(agents.codex)(m => agents.codex.withModel(Model(m)))
          case BackendTag.Opencode =>
            model.fold(agents.opencode)(m => agents.opencode.withModel(m))
          case BackendTag.Pi =>
            model.fold(agents.pi)(m => agents.pi.withModel(Model(m)))
          case BackendTag.Gemini =>
            model.fold(agents.gemini)(m => agents.gemini.withModel(Model(m)))
