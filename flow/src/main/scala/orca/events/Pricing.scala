package orca.events

import orca.agents.Model

import java.time.LocalDate
import scala.util.matching.Regex

/** Per-model token prices in USD per million tokens.
  *
  *   - `cacheRead` bills tokens served from cache (Claude
  *     `cache_read_input_tokens`, OpenAI `cached_input`).
  *   - `cacheWrite` bills tokens written into it (Claude
  *     `cache_creation_input_tokens`). Where a provider prices writes by cache
  *     lifetime, this is still a single rate: the CLI picks the TTL per request
  *     and orca never sees which tier a given write used. The shipped rates
  *     follow the tier the CLI in front of them actually requests; set your own
  *     accordingly.
  *   - `output` covers reasoning tokens too — both Anthropic and OpenAI bill
  *     reasoning at the output rate.
  *   - `ratesAsOf` is the date the rates were last checked against the
  *     provider's pricing page.
  *
  * No field defaults: a wrong cache-write rate is invisible in the summary,
  * whereas a missing argument is a compile error at the table that forgot it.
  */
case class ModelPricing(
    inputUsdPerMillion: BigDecimal,
    cacheReadUsdPerMillion: BigDecimal,
    outputUsdPerMillion: BigDecimal,
    cacheWriteUsdPerMillion: BigDecimal,
    ratesAsOf: LocalDate
)

/** Model id → per-million-token rates.
  *
  * Override by passing your own table to `flow(pricing = …)`:
  *
  * {{{
  * import orca.agents.Model
  * import java.time.LocalDate
  * flow(
  *   args,
  *   pricing = Pricing.default ++ Map(
  *     Model("my-model") -> ModelPricing(
  *       inputUsdPerMillion = 2,
  *       cacheReadUsdPerMillion = 0.2,
  *       outputUsdPerMillion = 10,
  *       cacheWriteUsdPerMillion = 2.5,
  *       ratesAsOf = LocalDate.of(2026, 9, 1)
  *     )
  *   )
  * ): ...
  * }}}
  */
type PricingTable = Map[Model, ModelPricing]

object Pricing:

  /** A dated-snapshot suffix, e.g. `-20251015` — the only shape [[lookup]]'s
    * prefix fallback bridges. Other suffixes (`-lite`, `-mini`, `-pro`, …) are
    * genuinely different, differently-priced tiers, not snapshots of the same
    * model.
    */
  private val DateSuffix: Regex = """^-\d{8}$""".r

  /** The CLI's 1M-context spelling of a Claude model, which costs what the base
    * row costs — so one row prices both spellings.
    */
  private val AliasSuffix: String = "[1m]"

  /** A shipped Anthropic row from its input rate alone: they all follow the
    * published ratios — cache read 0.10× input (unless given), output 5×, cache
    * write 2×.
    *
    * The 2× is the one-hour-TTL tier. Claude Code requests `ttl: "1h"`, and the
    * CLI's own usage breakdown confirms it: across measured runs every
    * cache-creation token landed in `cache_creation.ephemeral_1h_input_tokens`
    * and none in the 5m bucket. A backend asking for the five-minute TTL
    * instead (pi's default, unless `PI_CACHE_RETENTION=long`) bills 1.25×, so
    * override the table for pi-heavy use.
    */
  private def anthropic(inputUsdPerMillion: BigDecimal): ModelPricing =
    anthropic(inputUsdPerMillion, inputUsdPerMillion * BigDecimal("0.10"))

  private def anthropic(
      inputUsdPerMillion: BigDecimal,
      cacheReadUsdPerMillion: BigDecimal
  ): ModelPricing =
    ModelPricing(
      inputUsdPerMillion = inputUsdPerMillion,
      cacheReadUsdPerMillion = cacheReadUsdPerMillion,
      outputUsdPerMillion = inputUsdPerMillion * 5,
      cacheWriteUsdPerMillion = inputUsdPerMillion * 2,
      ratesAsOf = ShippedRatesAsOf
    )

  /** Resolve one call's cost: the figure the backend reported if there is one,
    * an [[estimate]] from `table` otherwise, `None` when neither is available.
    * The single home for the reported-vs-estimated decision.
    *
    * A reported zero against tokens actually spent is read as "no report" — a
    * backend that couldn't price the call (an unknown model, a subscription
    * plan), not a free call — so it falls through to the estimate. A zero on a
    * call that spent nothing is taken at face value.
    */
  def resolve(
      table: PricingTable,
      model: Option[Model],
      usage: Usage
  ): Option[Cost] =
    usage.cost
      .filterNot(_.signum == 0 && usage.spentTokens)
      .map(Cost(_, CostBasis.Reported))
      .orElse(estimate(table, model, usage))

  /** Compute an estimated cost for one call from `usage` and the price for
    * `model`. Returns `None` when `model` is missing, absent from `table`, or
    * the call spent no tokens at all — a zero estimate would be a `Cost` whose
    * `Estimated` basis relabels the whole run's total, having priced nothing.
    *
    * Looks up `model` exactly first, then falls back to the longest entry in
    * `table` that prefixes `model` — so a date-suffixed id like
    * `claude-sonnet-4-6-20251015` matches the `claude-sonnet-4-6` entry.
    */
  private def estimate(
      table: PricingTable,
      model: Option[Model],
      usage: Usage
  ): Option[Cost] =
    Option
      .when(usage.spentTokens)(model)
      .flatten
      .flatMap(lookup(table, _))
      .map: p =>
        val million = BigDecimal(1_000_000)
        val inputCost =
          BigDecimal(usage.freshInputTokens) * p.inputUsdPerMillion / million
        val cacheReadCost =
          BigDecimal(usage.cacheReadInputTokens) * p.cacheReadUsdPerMillion /
            million
        val cacheWriteCost =
          BigDecimal(usage.cacheWriteInputTokens) * p.cacheWriteUsdPerMillion /
            million
        val outputCost =
          BigDecimal(usage.outputTokens) * p.outputUsdPerMillion / million
        Cost(
          inputCost + cacheReadCost + cacheWriteCost + outputCost,
          CostBasis.Estimated(p.ratesAsOf)
        )

  /** Exact match, then the dated-snapshot prefix bridge, then the same lookup
    * again on the alias-stripped id. The alias strip runs LAST so a future
    * 1M-context model that prices differently wins with its own explicit row.
    */
  private def lookup(
      table: PricingTable,
      model: Model
  ): Option[ModelPricing] =
    exactOrDated(table, model).orElse:
      Option
        .when(model.name.endsWith(AliasSuffix))(
          Model(model.name.stripSuffix(AliasSuffix))
        )
        .flatMap(exactOrDated(table, _))

  private def exactOrDated(
      table: PricingTable,
      model: Model
  ): Option[ModelPricing] =
    table
      .get(model)
      .orElse:
        table.keys
          .filter: k =>
            model.name.startsWith(k.name) &&
              DateSuffix.matches(model.name.stripPrefix(k.name))
          .maxByOption(_.name.length)
          .flatMap(table.get)

  /** When every row of [[default]] was last checked. */
  private val ShippedRatesAsOf: LocalDate = LocalDate.of(2026, 9, 22)

  /** Default community-maintained pricing snapshot, in USD per million tokens.
    * Override by passing your own [[PricingTable]] to `flow(pricing = …)`.
    * These numbers go stale whenever a provider repacks its tiers, so re-check
    * against the provider's pages before relying on the estimate.
    */
  val default: PricingTable = Map(
    // --- Anthropic --- the argument is the input rate in USD per million;
    // `anthropic` derives the other three. Claude reports `total_cost_usd`
    // from the CLI, so these are mostly safety nets for sessions that didn't
    // surface the field — and the CLI computes that figure at these same
    // sticker rates.
    Model("claude-fable-5-1") -> anthropic(10, BigDecimal("0.25")),
    Model("claude-fable-5") -> anthropic(10),
    // Invitation-only, so no backend default pins it and
    // `DefaultModelsPricedTest` never reaches these rows — without them a
    // mythos turn shows tokens against no dollars.
    Model("claude-mythos-5-1") -> anthropic(10, BigDecimal("0.25")),
    Model("claude-mythos-5") -> anthropic(10),
    Model("claude-opus-5-5") -> anthropic(4, BigDecimal("0.20")),
    Model("claude-opus-5") -> anthropic(5),
    Model("claude-opus-4-8") -> anthropic(5),
    Model("claude-opus-4-7") -> anthropic(5),
    Model("claude-opus-4-6") -> anthropic(5),
    Model("claude-opus-4-5") -> anthropic(5),
    Model("claude-opus-4-1") -> anthropic(15),
    Model("claude-sonnet-5") -> anthropic(2),
    Model("claude-sonnet-4-6") -> anthropic(3),
    Model("claude-sonnet-4-5") -> anthropic(3),
    Model("claude-haiku-4-5") -> anthropic(1),
    // --- OpenAI (codex, opencode) ---
    // The GPT-5.6 and GPT-6 families price cache writes separately, at 1.25×
    // input; earlier models have no write charge, so their rate is plain
    // input. These are the short-context rates: prompts above 272K tokens
    // bill higher, so long-context turns are under-estimated.
    Model("gpt-6-astra") -> ModelPricing(
      inputUsdPerMillion = 10,
      cacheReadUsdPerMillion = 1,
      outputUsdPerMillion = 50,
      cacheWriteUsdPerMillion = 12.50,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-6-sol") -> ModelPricing(
      inputUsdPerMillion = 2,
      cacheReadUsdPerMillion = 0.20,
      outputUsdPerMillion = 10,
      cacheWriteUsdPerMillion = 2.50,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-6-luna") -> ModelPricing(
      inputUsdPerMillion = 0.10,
      cacheReadUsdPerMillion = 0.01,
      outputUsdPerMillion = 0.50,
      cacheWriteUsdPerMillion = 0.125,
      ratesAsOf = ShippedRatesAsOf
    ),
    // Promotional rate, announced to last at least through 2026-11-21.
    Model("gpt-5.6-sol") -> ModelPricing(
      inputUsdPerMillion = 4,
      cacheReadUsdPerMillion = 0.40,
      outputUsdPerMillion = 20,
      cacheWriteUsdPerMillion = 5,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-5.6-terra") -> ModelPricing(
      inputUsdPerMillion = 2.00,
      cacheReadUsdPerMillion = 0.20,
      outputUsdPerMillion = 12,
      cacheWriteUsdPerMillion = 2.50,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-5.6-luna") -> ModelPricing(
      inputUsdPerMillion = 0.20,
      cacheReadUsdPerMillion = 0.02,
      outputUsdPerMillion = 1.20,
      cacheWriteUsdPerMillion = 0.25,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-5") -> ModelPricing(
      inputUsdPerMillion = 1.25,
      cacheReadUsdPerMillion = 0.125,
      outputUsdPerMillion = 10,
      cacheWriteUsdPerMillion = 1.25,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-5-mini") -> ModelPricing(
      inputUsdPerMillion = 0.25,
      cacheReadUsdPerMillion = 0.025,
      outputUsdPerMillion = 2,
      cacheWriteUsdPerMillion = 0.25,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-5-nano") -> ModelPricing(
      inputUsdPerMillion = 0.05,
      cacheReadUsdPerMillion = 0.005,
      outputUsdPerMillion = 0.40,
      cacheWriteUsdPerMillion = 0.05,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gpt-5.4-mini") -> ModelPricing(
      inputUsdPerMillion = 0.75,
      cacheReadUsdPerMillion = 0.075,
      outputUsdPerMillion = 4.50,
      cacheWriteUsdPerMillion = 0.75,
      ratesAsOf = ShippedRatesAsOf
    ),
    // Gemini (paid tier). Pro is tiered on prompt size; these are the
    // ≤200k-token rates — prompts above 200k bill more (3.1 Pro: $4 in / $18
    // out), so a long-context flow, which is the usual shape here, is
    // UNDER-estimated. gemini emits no cost on the wire, so these table
    // rates × token counts are the only cost signal. The cache-write rate is
    // inert — the adapter never reports writes — and is set to the input
    // rate: implicit caching has no write charge, and explicit caching bills
    // storage per hour, which a token count can't express.
    Model("gemini-3.1-pro-preview") -> ModelPricing(
      inputUsdPerMillion = 2,
      cacheReadUsdPerMillion = 0.20,
      outputUsdPerMillion = 12,
      cacheWriteUsdPerMillion = 2,
      ratesAsOf = ShippedRatesAsOf
    ),
    // Promotional rate until 2026-12-31; $1.50 / $0.15 / $7.50 from
    // 2027-01-01.
    Model("gemini-3.8-flash") -> ModelPricing(
      inputUsdPerMillion = 0.75,
      cacheReadUsdPerMillion = 0.075,
      outputUsdPerMillion = 3.75,
      cacheWriteUsdPerMillion = 0.75,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gemini-3.5-flash") -> ModelPricing(
      inputUsdPerMillion = 1.50,
      cacheReadUsdPerMillion = 0.15,
      outputUsdPerMillion = 9,
      cacheWriteUsdPerMillion = 1.50,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gemini-2.5-pro") -> ModelPricing(
      inputUsdPerMillion = 1.25,
      cacheReadUsdPerMillion = 0.125,
      outputUsdPerMillion = 10,
      cacheWriteUsdPerMillion = 1.25,
      ratesAsOf = ShippedRatesAsOf
    ),
    Model("gemini-2.5-flash") -> ModelPricing(
      inputUsdPerMillion = 0.30,
      cacheReadUsdPerMillion = 0.03,
      outputUsdPerMillion = 2.50,
      cacheWriteUsdPerMillion = 0.30,
      ratesAsOf = ShippedRatesAsOf
    )
  )
