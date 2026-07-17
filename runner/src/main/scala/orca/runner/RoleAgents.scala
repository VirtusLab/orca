package orca.runner

import orca.agents.{Agent, BackendTag, Model}
import orca.settings.{AgentSettings, AgentSpec}

/** The three role agents resolved for one run — every field an existentially
  * typed [[Agent]] since planning/coding/review can each land on a different
  * backend. Opening the existential (dispatching on the concrete tag to call
  * `run`/`resultAs`) is the wiring task's job, not this one's.
  */
private[runner] case class ResolvedRoles(
    planning: Agent[?],
    coding: Agent[?],
    review: Agent[?]
)

private[runner] object RoleAgents:
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
