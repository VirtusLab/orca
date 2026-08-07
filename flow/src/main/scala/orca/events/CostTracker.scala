package orca.events

import orca.agents.Model

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/** Listener that accumulates `TokensUsed` events along three independent axes —
  * by `agent`, by `model` and by `role`. State is held in an `AtomicReference`
  * so the tracker is safe to register across concurrent LLM calls.
  *
  * Cost comes off the event, resolved once for the whole run by
  * [[CostResolvingDispatcher]] — the tracker prices nothing, so it cannot
  * report a figure that disagrees with the run's. `pricingAsOf` is the legend's
  * date only; pass the `lastUpdated` of the table the run prices with.
  *
  * All axes share the same underlying calls, so summing any of the maps yields
  * the grand total. The `model` axis keys on `Option[Model]` because the
  * reported model isn't always present; the summary surfaces the missing case
  * as `(unknown)`.
  */
class CostTracker(pricingAsOf: LocalDate) extends OrcaListener:

  /** One bucket's running total. Keeping the usage and the cost of the same
    * calls in one value is what stops the two drifting apart per axis.
    */
  private case class Tally(usage: Usage, cost: Option[Cost]):
    def +(that: Tally): Tally =
      Tally(usage + that.usage, (cost ++ that.cost).reduceOption(_ + _))

  private case class State(
      byAgent: Map[String, Tally] = Map.empty,
      byModel: Map[Option[Model], Tally] = Map.empty,
      /** The role last recorded for a given agent, for display/subtotal lookup.
        * `TokensUsed.role` is constant per agent in practice, so last-write and
        * first-write agree. See [[byRole]] for the subtotal axis.
        */
      agentRoles: Map[String, Option[String]] = Map.empty,
      byRole: Map[Option[String], Tally] = Map.empty,
      /** Whether any turn spent tokens the run could not price. A bucket mixing
        * priced and unpriced turns still has a `Tally.cost`, so this cannot be
        * derived from the maps afterwards.
        */
      anyUnpriced: Boolean = false
  ):
    def record(
        agent: String,
        model: Option[Model],
        usage: Usage,
        cost: Option[Cost],
        role: Option[String]
    ): State =
      val tally = Tally(usage, cost)
      copy(
        byAgent = add(byAgent, agent, tally),
        byModel = add(byModel, model, tally),
        agentRoles = agentRoles.updated(agent, role),
        byRole = add(byRole, role, tally),
        // A turn that spent nothing has nothing to price, so it is not a gap.
        anyUnpriced = anyUnpriced || (cost.isEmpty && usage.spentTokens)
      )

    private def add[K](
        buckets: Map[K, Tally],
        key: K,
        tally: Tally
    ): Map[K, Tally] =
      buckets.updatedWith(key)(prev => Some(prev.fold(tally)(_ + tally)))

  private val state: AtomicReference[State] = AtomicReference(State())

  def onEvent(event: OrcaEvent): Unit = event match
    // `attempt` is ignored: a retry's tokens count toward the run's spend like
    // any other turn's.
    case t: OrcaEvent.TokensUsed =>
      val _ = state.updateAndGet(
        _.record(t.agent, t.model, t.usage, t.cost, t.role)
      )
    case _ => ()

  /** Usage accumulated across every call, regardless of axis. */
  def total: Usage =
    state.get().byAgent.values.foldLeft(Usage.empty)(_ + _.usage)

  /** Total cost across every call. `None` when no call surfaced a cost
    * (reported or estimable).
    */
  def totalCost: Option[Cost] = totalCostOf(state.get())

  private def totalCostOf(s: State): Option[Cost] =
    s.byAgent.values.flatMap(_.cost).reduceOption(_ + _)

  /** Per-agent usage breakdown — keyed by `Agent.name`. */
  def perAgent: Map[String, Usage] =
    state.get().byAgent.view.mapValues(_.usage).toMap

  /** Per-agent cost breakdown. Missing entry means that agent's calls had
    * neither reported nor estimable cost.
    */
  def perAgentCost: Map[String, Cost] = costsOf(state.get().byAgent)

  /** Per-model usage breakdown. `None` collects calls whose model the backend
    * didn't report and the caller didn't pin in `AgentConfig`.
    */
  def perModel: Map[Option[Model], Usage] =
    state.get().byModel.view.mapValues(_.usage).toMap

  /** Per-model cost breakdown. Same key semantics as [[perModel]]. */
  def perModelCost: Map[Option[Model], Cost] = costsOf(state.get().byModel)

  /** Per-role usage breakdown ([[Agent.role]], e.g. `Some("reviewer")`). `None`
    * collects calls from an agent with no role tag — the common case.
    */
  def perRole: Map[Option[String], Usage] =
    state.get().byRole.view.mapValues(_.usage).toMap

  private def costsOf[K](buckets: Map[K, Tally]): Map[K, Cost] =
    buckets.collect { case (key, Tally(_, Some(cost))) => key -> cost }

  /** Two or three sections — by-agent, by-model, and (only when at least one
    * call carried a [[orca.agents.Agent.role]] tag) by-role — each sorted
    * alphabetically by its rendered label. Each by-agent line is prefixed with
    * that agent's role when it has one (e.g. `reviewer: performance`). Cache
    * reads, cache writes and reasoning tokens are shown parenthetically when
    * non-zero. Token counts are rendered compactly (`1K`, `103.8K`, `3.2M`)
    * from 1000 up; cost (when known) stays exact and is appended as `$X.XXXX`,
    * with an asterisk marking an estimated figure and a trailing legend line
    * when any estimate is present.
    *
    * A turn that spent tokens but resolved to no cost contributes nothing to
    * the total, so the total's label carries `(some turns unpriced)` and gains
    * its own legend line — otherwise a partial sum would read as the run's full
    * spend.
    *
    * Empty string when no `TokensUsed` events have been observed.
    */
  def summary: String =
    val s = state.get()
    if s.byAgent.isEmpty then ""
    else
      val agentLines = s.byAgent.toList
        .sortBy((agent, _) => agentLabel(agent, s.agentRoles))
        .map: (agent, t) =>
          s"  ${agentLabel(agent, s.agentRoles)}: ${formatLine(t)}"
      val modelLines = s.byModel.toList
        .sortBy((model, _) => modelLabel(model))
        .map: (model, t) =>
          s"  ${modelLabel(model)}: ${formatLine(t)}"
      val roleLines = s.byRole.toList
        .collect { case (Some(role), t) => (role, t) }
        .sortBy(_._1)
        .map: (role, t) =>
          s"  $role: ${formatLine(t)}"
      val roleSection =
        if roleLines.isEmpty then ""
        else s"""
                 |
                 |By role:
                 |${roleLines.mkString("\n")}""".stripMargin
      s"""By agent:
         |${agentLines.mkString("\n")}
         |
         |By model:
         |${modelLines.mkString(
          "\n"
        )}$roleSection${totalLine(s)}${legend(s)}""".stripMargin

  /** The run's total, qualified when some turns could not be priced. */
  private def totalLine(s: State): String =
    totalCostOf(s).fold(""): c =>
      // The "Estimated" prefix already conveys what the per-line asterisk
      // does, so we drop the marker on the total to avoid `Estimated
      // total: $1.10*` reading like double-counting.
      val label = if c.estimated then "Estimated total" else "Total"
      // Unqualified, the figure reads as the run's full spend; it is only
      // the sum of the turns that could be priced.
      val qualifier = if s.anyUnpriced then " (some turns unpriced)" else ""
      s"\n\n$label$qualifier: ${formatAmount(c)}"

  /** One explanation per caveat the summary raised; empty when it raised none.
    */
  private def legend(s: State): String =
    val hasEstimate = s.byAgent.values.flatMap(_.cost).exists(_.estimated)
    val legendLines = List(
      Option.when(hasEstimate)(
        s"* estimated from the pricing table " +
          s"(rates as of $pricingAsOf — may be stale)"
      ),
      Option.when(s.anyUnpriced)(
        "some turns had neither a reported cost nor a pricing-table row " +
          "— add the model via flow(pricing = …)"
      )
    ).flatten
    if legendLines.isEmpty then ""
    else legendLines.mkString("\n\n", "\n", "")

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

  private def formatLine(tally: Tally): String =
    val tokens = formatUsage(tally.usage)
    tally.cost.fold(tokens)(c => s"$tokens (${formatCost(c)})")

  /** Cache reads and cache writes share one parenthetical after the input
    * count, each part dropped when zero.
    */
  private def formatUsage(usage: Usage): String =
    val cacheParts = List(
      Option.when(usage.cacheReadInputTokens > 0)(
        s"${formatCount(usage.cacheReadInputTokens)} cache read"
      ),
      Option.when(usage.cacheWriteInputTokens > 0)(
        s"${formatCount(usage.cacheWriteInputTokens)} cache write"
      )
    ).flatten
    val cache =
      if cacheParts.isEmpty then "" else cacheParts.mkString(" (", ", ", ")")
    val reasoning =
      if usage.reasoningOutputTokens > 0 then
        s" (${formatCount(usage.reasoningOutputTokens)} reasoning)"
      else ""
    val in = formatCount(usage.inputTokens)
    val out = formatCount(usage.outputTokens)
    s"$in in$cache, $out out$reasoning"

  /** Render a token count compactly: plain digits below 1000, from 1000 up one
    * decimal place with a K/M/B suffix and no trailing `.0` — `1K`, `103.8K`,
    * `3.2M`. Halves round up.
    */
  private def formatCount(n: Long): String =
    if n < 1000 then n.toString
    else
      val units = List(1_000L -> "K", 1_000_000L -> "M", 1_000_000_000L -> "B")
      def mantissa(unit: Long): BigDecimal =
        (BigDecimal(n) / unit).setScale(1, BigDecimal.RoundingMode.HALF_UP)
      // Ascending scan for the first unit whose *rounded* mantissa stays under
      // 1000: rounding up carries 999,950 to "1000.0K", which belongs one unit
      // higher as "1M". `getOrElse` covers counts past the largest unit.
      val (unit, suffix) =
        units.find((u, _) => mantissa(u) < 1000).getOrElse(units.last)
      // Scale is fixed at 1, so a plain suffix strip turns "2.0" into "2".
      s"${mantissa(unit).toString.stripSuffix(".0")}$suffix"

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
