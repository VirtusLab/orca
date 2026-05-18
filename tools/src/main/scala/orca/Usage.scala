package orca

/** Token + cost accounting for one or more LLM calls.
  *
  * `inputTokens` / `outputTokens` are the totals as billed by the backend.
  * `cachedInputTokens` is the sub-portion of `inputTokens` served from prompt
  * cache (cache_creation + cache_read for Claude, `cached_input_tokens` for
  * codex); `reasoningOutputTokens` is the sub-portion of `outputTokens` that
  * the model spent on internal reasoning (codex / o-series). Both sub-counts
  * are non-cumulative breakdowns and are surfaced separately so callers can
  * report cache hit ratios and reasoning overhead without doing the maths
  * themselves.
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
