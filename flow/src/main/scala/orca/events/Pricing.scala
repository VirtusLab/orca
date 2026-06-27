package orca.events

import orca.agents.Model

import java.time.LocalDate

/** Per-model token prices in USD per million tokens. `cachedInput` is the
  * cache-read rate (Claude `cache_read_input_tokens`, OpenAI `cached_input`);
  * orca doesn't separate cache-create from cache-read so the rate that
  * dominates after the first turn is the one to put here. `output` covers
  * reasoning tokens too — both Anthropic and OpenAI bill reasoning at the
  * output rate.
  */
case class ModelPricing(
    inputUsdPerMillion: BigDecimal,
    cachedInputUsdPerMillion: BigDecimal,
    outputUsdPerMillion: BigDecimal
)

/** A USD cost with a flag that propagates through addition: any aggregate
  * mixing at least one estimated input is itself flagged as an estimate.
  */
case class Cost(amount: BigDecimal, estimated: Boolean):
  def +(that: Cost): Cost =
    Cost(amount + that.amount, estimated || that.estimated)

/** Model id → per-million-token rates. */
type PricingTable = Map[Model, ModelPricing]

/** A pricing table paired with the date its numbers were last sanity-checked
  * against provider pricing pages. The date is surfaced in the cost-summary
  * legend so users can judge how stale an estimated figure might be — provider
  * pricing changes silently, so an old snapshot can drift even though the
  * estimate looks authoritative.
  *
  * Override by passing your own `PriceList` to `flow(pricing = …)`:
  *
  * {{{
  * import java.time.LocalDate
  * flow(
  *   args,
  *   pricing = PriceList(
  *     Pricing.default.table ++ Map("my-model" -> ModelPricing(2, 0.2, 10)),
  *     lastUpdated = LocalDate.now
  *   )
  * ): ...
  * }}}
  */
case class PriceList(table: PricingTable, lastUpdated: LocalDate)

object Pricing:

  /** Compute an estimated cost for one call from `usage` and the price for
    * `model`. Returns `None` when `model` is missing or absent from `table`.
    *
    * Looks up `model` exactly first, then falls back to the longest entry in
    * `table` that prefixes `model` — so a date-suffixed id like
    * `claude-sonnet-4-6-20251015` matches the `claude-sonnet-4-6` entry.
    */
  def estimate(
      table: PricingTable,
      model: Option[Model],
      usage: Usage
  ): Option[BigDecimal] =
    model
      .flatMap(lookup(table, _))
      .map: p =>
        val million = BigDecimal(1_000_000)
        val billableInput = (usage.inputTokens - usage.cachedInputTokens) max 0L
        val inputCost =
          BigDecimal(billableInput) * p.inputUsdPerMillion / million
        val cachedCost =
          BigDecimal(usage.cachedInputTokens) * p.cachedInputUsdPerMillion /
            million
        val outputCost =
          BigDecimal(usage.outputTokens) * p.outputUsdPerMillion / million
        inputCost + cachedCost + outputCost

  private def lookup(
      table: PricingTable,
      model: Model
  ): Option[ModelPricing] =
    table
      .get(model)
      .orElse:
        table.keys
          .filter(k => model.name.startsWith(k.name))
          .maxByOption(_.name.length)
          .flatMap(table.get)

  /** Default community-maintained pricing snapshot, in USD per million tokens.
    * Override by passing your own [[PriceList]] (or one derived from this one)
    * to `flow(pricing = …)`. Re-check against the provider's pricing pages
    * before relying on the estimate — these numbers go stale whenever a
    * provider repacks their tiers. [[PriceList.lastUpdated]] is the date the
    * baked-in numbers were last verified, and is surfaced in the cost-summary
    * legend so the user can tell at a glance whether the snapshot is fresh.
    */
  val default: PriceList = PriceList(
    table = Map(
      // --- Anthropic ---
      // Claude reports `total_cost_usd` from the CLI, so these are mostly
      // safety nets for sessions that didn't surface the field.
      Model("claude-fable-5") -> ModelPricing(10, 1.00, 50),
      Model("claude-opus-4-7") -> ModelPricing(15, 1.50, 75),
      Model("claude-opus-4-6") -> ModelPricing(15, 1.50, 75),
      Model("claude-opus-4-5") -> ModelPricing(15, 1.50, 75),
      Model("claude-opus-4-1") -> ModelPricing(15, 1.50, 75),
      Model("claude-sonnet-4-6") -> ModelPricing(3, 0.30, 15),
      Model("claude-sonnet-4-5") -> ModelPricing(3, 0.30, 15),
      Model("claude-haiku-4-5") -> ModelPricing(1, 0.10, 5),
      // --- OpenAI (codex) ---
      Model("gpt-5") -> ModelPricing(1.25, 0.125, 10),
      Model("gpt-5-mini") -> ModelPricing(0.25, 0.025, 2),
      Model("gpt-5-nano") -> ModelPricing(0.05, 0.005, 0.40),
      // codex CLI 0.125.x default
      Model("gpt-5.4-mini") -> ModelPricing(0.25, 0.025, 2),
      // Gemini (paid tier). 2.5 Pro is tiered on prompt size; these are the
      // ≤200k-token rates (the common case) — prompts above 200k bill more
      // ($2.50 in / $15 out). gemini emits no cost on the wire, so these
      // table rates × token counts are the only cost signal.
      Model("gemini-2.5-pro") -> ModelPricing(1.25, 0.31, 10),
      Model("gemini-2.5-flash") -> ModelPricing(0.30, 0.075, 2.50)
    ),
    lastUpdated = LocalDate.of(2026, 6, 10)
  )
