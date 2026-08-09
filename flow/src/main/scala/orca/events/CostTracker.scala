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
  * All axes share the same underlying calls, so summing any of the maps — or
  * any section the summary renders — yields the grand total. The `model` and
  * `role` axes key on `Option` because neither the reported model nor a role
  * tag is always present.
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

  /** Two or three sections — by-agent, by-model and by-role — each sorted
    * alphabetically by its rendered label. The by-role section appears only
    * when some call carried a [[orca.agents.Agent.role]] tag, and then includes
    * the `(untagged)` bucket too, so it still sums to the run's total. Each
    * by-agent line is prefixed with that agent's role when it has one (e.g.
    * `reviewer: performance`). Cache reads, cache writes and reasoning tokens
    * are shown parenthetically when non-zero. Token counts are rendered
    * compactly (`1K`, `103.8K`, `3.2M`) from 1000 up, a count and its
    * parenthetical breakdown at one shared unit (`1.63M in (1.15M cache read,
    * 0.48M cache write)`); cost (when known) stays exact and is appended as
    * `$X.XXXX`, with an asterisk marking an estimated figure and a trailing
    * legend line when any estimate is present.
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
        .sortBy((role, _) => roleLabel(role))
        .map: (role, t) =>
          s"  ${roleLabel(role)}: ${formatLine(t)}"
      val anyRoleTagged = s.byRole.keys.exists(_.isDefined)
      val roleSection =
        if !anyRoleTagged then ""
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
        "some turns had no usable cost and no pricing-table row " +
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

  /** Render a role bucket key for the summary. `None` covers calls from an
    * agent with no role tag — too varied to name for any one role.
    */
  private def roleLabel(role: Option[String]): String =
    role.getOrElse("(untagged)")

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

  /** Input and output are two independent groups: cache reads and writes are
    * parts of the input count, reasoning a part of the output count.
    */
  private def formatUsage(usage: Usage): String =
    val in = formatGroup(
      usage.inputTokens,
      "in",
      List(
        usage.cacheReadInputTokens -> "cache read",
        usage.cacheWriteInputTokens -> "cache write"
      )
    )
    val out = formatGroup(
      usage.outputTokens,
      "out",
      List(usage.reasoningOutputTokens -> "reasoning")
    )
    s"$in, $out"

  /** `<total> <totalLabel>`, followed by the non-zero `parts` in one
    * parenthetical.
    */
  private def formatGroup(
      total: Long,
      totalLabel: String,
      parts: List[(Long, String)]
  ): String =
    val nonZeroParts = parts.filter((n, _) => n > 0)
    val scale = CostTracker.CountScale.of(total, nonZeroParts.map((n, _) => n))
    val head = s"${scale.format(total)} $totalLabel"
    if nonZeroParts.isEmpty then head
    else
      val breakdown = nonZeroParts
        .map((n, partLabel) => s"${scale.format(n)} $partLabel")
        .mkString(", ")
      s"$head ($breakdown)"

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

private object CostTracker:

  /** The unit and decimal count shared by one group of counts — a total and the
    * counts that break it down. One unit across the group is what lets the
    * parts be read against the total without a conversion in between, and no
    * part is ever printed larger than the total it came from.
    */
  private case class CountScale(unit: Long, suffix: String, decimals: Int):

    /** `n` at this scale: a mantissa with a K/M/B suffix, halves rounding up. A
      * trailing `.0` is dropped, but a two-decimal group keeps its zeros, so
      * every count in it is shown to the same precision.
      *
      * A count below a tenth of the unit keeps its OWN scale instead: at this
      * one it would lose its leading significant digit (5 tokens as `0.01K`),
      * and it is far too small to move the total it sits under.
      */
    def format(n: Long): String =
      if this == CountScale.Plain then n.toString
      else if n < unit / 10 then CountScale.of(n, Nil).format(n)
      else
        val mantissa = (BigDecimal(n) / unit)
          .setScale(decimals, BigDecimal.RoundingMode.HALF_UP)
        val digits =
          if decimals == 1 then mantissa.toString.stripSuffix(".0")
          else mantissa.toString
        s"$digits$suffix"

  private object CountScale:

    /** Counts small enough to print in full. */
    private val Plain = CountScale(1L, "", 0)

    private val units =
      List(1_000L -> "K", 1_000_000L -> "M", 1_000_000_000L -> "B")

    /** The scale for a group with this `total` and these non-zero `parts`. The
      * unit comes from the total, always the largest count in the group. A part
      * that lands below one unit takes the whole group to two decimals so it
      * shows a digit rather than `0.0M`; the total never needs that, its own
      * mantissa being what picked the unit.
      */
    def of(total: Long, parts: List[Long]): CountScale =
      if total < 1000 then Plain
      else
        def mantissa(unit: Long): BigDecimal =
          (BigDecimal(total) / unit)
            .setScale(1, BigDecimal.RoundingMode.HALF_UP)
        // Ascending scan for the first unit whose *rounded* mantissa stays
        // under 1000: rounding up carries 999,950 to "1000.0K", which belongs
        // one unit higher as "1M". `getOrElse` covers counts past the largest
        // unit.
        val (unit, suffix) =
          units.find((u, _) => mantissa(u) < 1000).getOrElse(units.last)
        val subUnitPart = parts.exists(p => p >= unit / 10 && p < unit)
        CountScale(unit, suffix, if subUnitPart then 2 else 1)
