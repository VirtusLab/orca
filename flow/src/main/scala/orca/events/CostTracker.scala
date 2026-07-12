package orca.events

import orca.agents.Model

import java.util.concurrent.atomic.AtomicReference

/** Listener that accumulates `TokensUsed` events along two independent axes —
  * by `agent` (e.g. `claude`, `abstraction`, `performance`) and by `model`
  * (e.g. `claude-opus-4-7`, `claude-haiku-4-5`). State is held in an
  * `AtomicReference` so the tracker is safe to register across concurrent LLM
  * calls.
  *
  * Cost: each event either carries a reported figure (Claude CLI returns
  * `total_cost_usd`) or has its cost estimated from the supplied [[PriceList]].
  * Estimated costs are flagged so the user can see when the number depends on a
  * maintained price table rather than the backend's own figure; the legend
  * shows the table's `lastUpdated` date so a stale snapshot is obvious. Pass a
  * custom `pricing` to override the default rates.
  *
  * Both axes share the same set of underlying calls, so summing either map
  * yields the grand total. The `model` axis stores `Option[Model]` because the
  * response's reported model isn't always present; the by-model summary
  * surfaces the missing case as `(unknown)`.
  */
class CostTracker(pricing: PriceList = Pricing.default) extends OrcaListener:

  private case class State(
      byAgent: Map[String, Usage] = Map.empty,
      byModel: Map[Option[Model], Usage] = Map.empty,
      byAgentCost: Map[String, Cost] = Map.empty,
      byModelCost: Map[Option[Model], Cost] = Map.empty,
      /** The role last recorded for a given agent — `TokensUsed.role` is
        * constant per agent in practice (an agent's role is set once, at the
        * emission edge, before any of its calls), so "last write" and "first
        * write" agree; this is purely a display/subtotal lookup, not itself a
        * source of truth for anything. See [[byRole]] for the subtotal axis.
        */
      agentRoles: Map[String, Option[String]] = Map.empty,
      byRole: Map[Option[String], Usage] = Map.empty,
      byRoleCost: Map[Option[String], Cost] = Map.empty
  ):
    def record(
        agent: String,
        model: Option[Model],
        usage: Usage,
        cost: Option[Cost],
        role: Option[String]
    ): State = copy(
      byAgent =
        byAgent.updated(agent, byAgent.getOrElse(agent, Usage.empty) + usage),
      byModel =
        byModel.updated(model, byModel.getOrElse(model, Usage.empty) + usage),
      byAgentCost = addCost(byAgentCost, agent, cost),
      byModelCost = addCost(byModelCost, model, cost),
      agentRoles = agentRoles.updated(agent, role),
      byRole =
        byRole.updated(role, byRole.getOrElse(role, Usage.empty) + usage),
      byRoleCost = addCost(byRoleCost, role, cost)
    )

  /** Fold one optional cost into a per-key map. No-op when `cost` is `None`
    * (the call had neither a reported nor estimable figure).
    */
  private def addCost[K](
      map: Map[K, Cost],
      key: K,
      cost: Option[Cost]
  ): Map[K, Cost] =
    cost.fold(map)(c => map.updated(key, map.get(key).fold(c)(_ + c)))

  private val state: AtomicReference[State] = AtomicReference(State())

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.TokensUsed(agent, model, usage, role) =>
      val cost = costFor(model, usage)
      val _ = state.updateAndGet(_.record(agent, model, usage, cost, role))
    case _ => ()

  /** Resolve a per-call cost: the reported figure if the backend supplied one,
    * otherwise an estimate from the pricing table. `None` when neither is
    * available (no `total_cost_usd` and no table entry for the model).
    */
  private def costFor(model: Option[Model], usage: Usage): Option[Cost] =
    usage.cost
      .map(amount => Cost(amount, estimated = false))
      .orElse(
        Pricing
          .estimate(pricing.table, model, usage)
          .map(amount => Cost(amount, estimated = true))
      )

  /** Usage accumulated across every call, regardless of axis. */
  def total: Usage =
    state.get().byAgent.values.foldLeft(Usage.empty)(_ + _)

  /** Total cost across every call. `None` when no call surfaced a cost
    * (reported or estimable).
    */
  def totalCost: Option[Cost] =
    state.get().byAgentCost.values.reduceOption(_ + _)

  /** Per-agent usage breakdown — keyed by `Agent.name`. */
  def perAgent: Map[String, Usage] = state.get().byAgent

  /** Per-agent cost breakdown. Missing entry means that agent's calls had
    * neither reported nor estimable cost.
    */
  def perAgentCost: Map[String, Cost] = state.get().byAgentCost

  /** Per-model usage breakdown. `None` collects calls whose model the backend
    * didn't report and the caller didn't pin in `AgentConfig`.
    */
  def perModel: Map[Option[Model], Usage] = state.get().byModel

  /** Per-model cost breakdown. Same key semantics as [[perModel]]. */
  def perModelCost: Map[Option[Model], Cost] = state.get().byModelCost

  /** Per-role usage breakdown ([[Agent.role]], e.g. `Some("reviewer")`). `None`
    * collects calls from an agent with no role tag — the common case.
    */
  def perRole: Map[Option[String], Usage] = state.get().byRole

  /** Two or three sections — by-agent, by-model, and (only when at least one
    * call carried a [[orca.agents.Agent.role]] tag) by-role — each sorted
    * alphabetically by its RENDERED label, so the ordering the reader sees is
    * the ordering applied. Each by-agent line is prefixed with that agent's
    * role when it has one (e.g. `reviewer: performance`), purely a display
    * derivation from [[State.agentRoles]] — `agent` itself is never mutated to
    * carry it. Cache hits and reasoning tokens are shown parenthetically when
    * non-zero, and cost (when known) is appended as `$X.XXXX`. An asterisk
    * after the dollar amount marks an estimated figure; a trailing legend line
    * explains it when at least one estimate is present.
    *
    * Empty string when no `TokensUsed` events have been observed.
    */
  def summary: String =
    val s = state.get()
    if s.byAgent.isEmpty then ""
    else
      val agentLines = s.byAgent.toList
        .sortBy((agent, _) => agentLabel(agent, s.agentRoles))
        .map: (agent, u) =>
          s"  ${agentLabel(agent, s.agentRoles)}: ${formatLine(u, s.byAgentCost.get(agent))}"
      val modelLines = s.byModel.toList
        .sortBy((model, _) => modelLabel(model))
        .map: (model, u) =>
          s"  ${modelLabel(model)}: ${formatLine(u, s.byModelCost.get(model))}"
      val roleLines = s.byRole.toList
        .collect { case (Some(role), u) => (role, u) }
        .sortBy(_._1)
        .map: (role, u) =>
          s"  $role: ${formatLine(u, s.byRoleCost.get(Some(role)))}"
      val roleSection =
        if roleLines.isEmpty then ""
        else s"""
                 |
                 |By role:
                 |${roleLines.mkString("\n")}""".stripMargin
      val totalLine = totalCost.fold(""): c =>
        // The "Estimated" prefix already conveys what the per-line asterisk
        // does, so we drop the marker on the total to avoid `Estimated
        // total: $1.10*` reading like double-counting.
        val label = if c.estimated then "Estimated total" else "Total"
        s"\n\n$label: ${formatAmount(c)}"
      val hasEstimate =
        (s.byAgentCost.values ++ s.byModelCost.values).exists(_.estimated)
      val legend =
        if hasEstimate then
          s"\n\n* estimated from the pricing table " +
            s"(rates as of ${pricing.lastUpdated} — may be stale)"
        else ""
      s"""By agent:
         |${agentLines.mkString("\n")}
         |
         |By model:
         |${modelLines.mkString(
          "\n"
        )}$roleSection$totalLine$legend""".stripMargin

  /** Render a model bucket key for the summary. `None` covers calls whose model
    * the backend didn't report.
    */
  private def modelLabel(model: Option[Model]): String =
    model.map(_.name).getOrElse("(unknown)")

  /** Render a by-agent line's label: the bare agent name, prefixed with its
    * role (looked up in `agentRoles`) when it has one. The `"reviewer: "`
    * prefix is derived purely for display, never baked into `agent` itself.
    */
  private def agentLabel(
      agent: String,
      agentRoles: Map[String, Option[String]]
  ): String =
    agentRoles.get(agent).flatten.fold(agent)(role => s"$role: $agent")

  private def formatLine(usage: Usage, cost: Option[Cost]): String =
    val tokens = formatUsage(usage)
    cost.fold(tokens)(c => s"$tokens (${formatCost(c)})")

  private def formatUsage(usage: Usage): String =
    val cached =
      if usage.cachedInputTokens > 0 then
        s" (${usage.cachedInputTokens} cached)"
      else ""
    val reasoning =
      if usage.reasoningOutputTokens > 0 then
        s" (${usage.reasoningOutputTokens} reasoning)"
      else ""
    s"${usage.inputTokens} in$cached, ${usage.outputTokens} out$reasoning"

  private def formatAmount(c: Cost): String =
    val rounded = c.amount.setScale(4, BigDecimal.RoundingMode.HALF_UP)
    s"$$$rounded"

  private def formatCost(c: Cost): String =
    val marker = if c.estimated then "*" else ""
    s"${formatAmount(c)}$marker"

  /** Print the summary on its own block. Leading newline keeps the output from
    * landing on top of an active terminal status row; trailing newline ensures
    * the last line is committed.
    */
  def printSummary(): Unit =
    val s = summary
    if s.nonEmpty then println(s"\n$s")
