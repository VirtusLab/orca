package orca.events

/** Token + cost accounting for one or more LLM calls.
  *
  * **Normalisation contract.** All backends map onto the same axes so summing
  * `Usage` across calls and backends is apples-to-apples:
  *
  *   - `inputTokens` is the TOTAL prompt tokens, **inclusive** of any served
  *     from prompt cache. `outputTokens` is the total completion tokens.
  *   - `cachedInputTokens` is the cache-served sub-portion (`<= inputTokens`);
  *     `reasoningOutputTokens` is the internal-reasoning sub-portion of
  *     `outputTokens` (codex / o-series). Both are non-cumulative breakdowns so
  *     callers can report cache-hit and reasoning ratios directly.
  *
  * A new backend must fold any cache-served tokens INTO `inputTokens` rather
  * than report them alongside it. The per-backend arithmetic is documented at
  * each driver's `Usage(...)` construction site.
  */
case class Usage(
    inputTokens: Long,
    outputTokens: Long,
    cost: Option[BigDecimal],
    cachedInputTokens: Long = 0L,
    reasoningOutputTokens: Long = 0L
):
  /** Combine two usages; cost is `Some` iff at least one side reports it. */
  def +(that: Usage): Usage =
    Usage(
      inputTokens = inputTokens + that.inputTokens,
      outputTokens = outputTokens + that.outputTokens,
      cost = (cost ++ that.cost).reduceOption(_ + _),
      cachedInputTokens = cachedInputTokens + that.cachedInputTokens,
      reasoningOutputTokens = reasoningOutputTokens + that.reasoningOutputTokens
    )

object Usage:
  val empty: Usage = Usage(0L, 0L, None, 0L, 0L)
