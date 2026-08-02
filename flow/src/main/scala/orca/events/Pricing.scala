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
  *     lifetime — Anthropic charges 1.25× base input at the five-minute TTL and
  *     2× at the one-hour one — this is a single rate, because the CLI picks
  *     the TTL per request and orca never sees which tier a given write used.
  *     The shipped rates follow the tier the CLI in front of them actually
  *     requests; set your own accordingly.
  *   - `output` covers reasoning tokens too — both Anthropic and OpenAI bill
  *     reasoning at the output rate.
  *
  * No field defaults: a wrong cache-write rate is invisible in the summary,
  * whereas a missing argument is a compile error at the table that forgot it.
  */
case class ModelPricing(
    inputUsdPerMillion: BigDecimal,
    cacheReadUsdPerMillion: BigDecimal,
    outputUsdPerMillion: BigDecimal,
    cacheWriteUsdPerMillion: BigDecimal
)

/** A USD cost with a flag that propagates through addition: any aggregate
  * mixing at least one estimated input is itself flagged as an estimate.
  */
case class Cost(amount: BigDecimal, estimated: Boolean):
  def +(that: Cost): Cost =
    Cost(amount + that.amount, estimated || that.estimated)

/** Model id → per-million-token rates. */
type PricingTable = Map[Model, ModelPricing]

/** A pricing table paired with the date its numbers were last checked against
  * provider pricing pages. The date is surfaced in the cost-summary legend so
  * users can judge how stale an estimate might be.
  *
  * Override by passing your own `PriceList` to `flow(pricing = …)`:
  *
  * {{{
  * import orca.agents.Model
  * import java.time.LocalDate
  * flow(
  *   args,
  *   pricing = PriceList(
  *     Pricing.default.table ++
  *       Map(Model("my-model") -> ModelPricing(2, 0.2, 10, 2.5)),
  *     lastUpdated = LocalDate.now
  *   )
  * ): ...
  * }}}
  */
case class PriceList(table: PricingTable, lastUpdated: LocalDate)

object Pricing:

  /** A dated-snapshot suffix, e.g. `-20251015` — the only shape [[lookup]]'s
    * prefix fallback bridges. Other suffixes (`-lite`, `-mini`, `-pro`, …) are
    * genuinely different, differently-priced tiers, not snapshots of the same
    * model.
    */
  private val DateSuffix: Regex = """^-\d{8}$""".r

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
        // Fresh input is what neither cache category claimed. The clamp keeps
        // a backend that over-reports its cache axes from producing a negative
        // charge.
        val freshInput = (usage.inputTokens - usage.cacheReadInputTokens -
          usage.cacheWriteInputTokens) max 0L
        val inputCost =
          BigDecimal(freshInput) * p.inputUsdPerMillion / million
        val cacheReadCost =
          BigDecimal(usage.cacheReadInputTokens) * p.cacheReadUsdPerMillion /
            million
        val cacheWriteCost =
          BigDecimal(usage.cacheWriteInputTokens) * p.cacheWriteUsdPerMillion /
            million
        val outputCost =
          BigDecimal(usage.outputTokens) * p.outputUsdPerMillion / million
        inputCost + cacheReadCost + cacheWriteCost + outputCost

  private def lookup(
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

  /** Default community-maintained pricing snapshot, in USD per million tokens.
    * Override by passing your own [[PriceList]] to `flow(pricing = …)`. These
    * numbers go stale whenever a provider repacks its tiers, so re-check
    * against the provider's pages before relying on the estimate.
    */
  val default: PriceList = PriceList(
    table = Map(
      // --- Anthropic ---
      // Claude reports `total_cost_usd` from the CLI, so these are mostly
      // safety nets for sessions that didn't surface the field. Cache-write
      // rates are the one-hour-TTL tier (2× base input): Claude Code requests
      // `ttl: "1h"`, and the CLI's own usage breakdown confirms it — across
      // measured runs every cache-creation token landed in
      // `cache_creation.ephemeral_1h_input_tokens` and none in the 5m bucket.
      // A backend that asks for the five-minute TTL instead (pi's default,
      // unless PI_CACHE_RETENTION=long) bills 1.25× — $6.25 Opus, $3.75
      // Sonnet, $1.25 Haiku, $12.50 Fable — so override the table for
      // pi-heavy use.
      Model("claude-fable-5") -> ModelPricing(
        inputUsdPerMillion = 10,
        cacheReadUsdPerMillion = 1.00,
        outputUsdPerMillion = 50,
        cacheWriteUsdPerMillion = 20
      ),
      // `[1m]` is the CLI's 1M-context spelling of the same model at the same
      // price; the suffix isn't a date, so `lookup` won't bridge it — hence its
      // own row.
      Model("claude-opus-5") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-5[1m]") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-4-8") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-4-8[1m]") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-4-7") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-4-6") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-4-5") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 25,
        cacheWriteUsdPerMillion = 10
      ),
      Model("claude-opus-4-1") -> ModelPricing(
        inputUsdPerMillion = 15,
        cacheReadUsdPerMillion = 1.50,
        outputUsdPerMillion = 75,
        cacheWriteUsdPerMillion = 30
      ),
      // The CLI computes the `total_cost_usd` it reports at these sticker
      // rates; Anthropic's published introductory $2/$10 ends 2026-08-31.
      Model("claude-sonnet-5") -> ModelPricing(
        inputUsdPerMillion = 3,
        cacheReadUsdPerMillion = 0.30,
        outputUsdPerMillion = 15,
        cacheWriteUsdPerMillion = 6
      ),
      Model("claude-sonnet-4-6") -> ModelPricing(
        inputUsdPerMillion = 3,
        cacheReadUsdPerMillion = 0.30,
        outputUsdPerMillion = 15,
        cacheWriteUsdPerMillion = 6
      ),
      Model("claude-sonnet-4-5") -> ModelPricing(
        inputUsdPerMillion = 3,
        cacheReadUsdPerMillion = 0.30,
        outputUsdPerMillion = 15,
        cacheWriteUsdPerMillion = 6
      ),
      Model("claude-haiku-4-5") -> ModelPricing(
        inputUsdPerMillion = 1,
        cacheReadUsdPerMillion = 0.10,
        outputUsdPerMillion = 5,
        cacheWriteUsdPerMillion = 2
      ),
      // --- OpenAI (codex, opencode) ---
      // The GPT-5.6 family prices cache writes separately, at 1.25× input;
      // earlier models have no write charge, so their rate is plain input.
      Model("gpt-5.6-sol") -> ModelPricing(
        inputUsdPerMillion = 5,
        cacheReadUsdPerMillion = 0.50,
        outputUsdPerMillion = 30,
        cacheWriteUsdPerMillion = 6.25
      ),
      Model("gpt-5.6-terra") -> ModelPricing(
        inputUsdPerMillion = 2.00,
        cacheReadUsdPerMillion = 0.20,
        outputUsdPerMillion = 12,
        cacheWriteUsdPerMillion = 2.50
      ),
      Model("gpt-5.6-luna") -> ModelPricing(
        inputUsdPerMillion = 0.20,
        cacheReadUsdPerMillion = 0.02,
        outputUsdPerMillion = 1.20,
        cacheWriteUsdPerMillion = 0.25
      ),
      Model("gpt-5") -> ModelPricing(
        inputUsdPerMillion = 1.25,
        cacheReadUsdPerMillion = 0.125,
        outputUsdPerMillion = 10,
        cacheWriteUsdPerMillion = 1.25
      ),
      Model("gpt-5-mini") -> ModelPricing(
        inputUsdPerMillion = 0.25,
        cacheReadUsdPerMillion = 0.025,
        outputUsdPerMillion = 2,
        cacheWriteUsdPerMillion = 0.25
      ),
      Model("gpt-5-nano") -> ModelPricing(
        inputUsdPerMillion = 0.05,
        cacheReadUsdPerMillion = 0.005,
        outputUsdPerMillion = 0.40,
        cacheWriteUsdPerMillion = 0.05
      ),
      // codex CLI 0.125.x default
      Model("gpt-5.4-mini") -> ModelPricing(
        inputUsdPerMillion = 0.75,
        cacheReadUsdPerMillion = 0.075,
        outputUsdPerMillion = 4.50,
        cacheWriteUsdPerMillion = 0.75
      ),
      // Gemini (paid tier). 2.5 Pro is tiered on prompt size; these are the
      // ≤200k-token rates — prompts above 200k bill double ($2.50 in / $15
      // out), so a long-context flow, which is the usual shape here, is
      // UNDER-estimated by up to half. gemini emits no cost on the wire, so these
      // table rates × token counts are the only cost signal. The cache-write
      // rate is inert — the adapter never reports writes — and is set to the
      // input rate: implicit caching has no write charge, and explicit
      // caching bills storage per hour, which a token count can't express.
      Model("gemini-2.5-pro") -> ModelPricing(
        inputUsdPerMillion = 1.25,
        cacheReadUsdPerMillion = 0.125,
        outputUsdPerMillion = 10,
        cacheWriteUsdPerMillion = 1.25
      ),
      Model("gemini-2.5-flash") -> ModelPricing(
        inputUsdPerMillion = 0.30,
        cacheReadUsdPerMillion = 0.03,
        outputUsdPerMillion = 2.50,
        cacheWriteUsdPerMillion = 0.30
      )
    ),
    lastUpdated = LocalDate.of(2026, 8, 2)
  )
