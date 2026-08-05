package orca.events

/** Token + cost accounting for one or more LLM calls.
  *
  * **Normalisation contract.** All backends map onto the same axes so summing
  * `Usage` across calls and backends is apples-to-apples:
  *
  *   - `inputTokens` is the TOTAL prompt tokens, **inclusive** of any served
  *     from prompt cache or written into it. `outputTokens` is the total
  *     completion tokens.
  *   - `cacheReadInputTokens` (tokens served FROM the prompt cache) and
  *     `cacheWriteInputTokens` (tokens written INTO it — Anthropic spells this
  *     "cache creation") are DISJOINT sub-portions of `inputTokens`, so
  *     `cacheReadInputTokens + cacheWriteInputTokens <= inputTokens`. They are
  *     separate axes because they bill at opposite ends of base input — a write
  *     costs more, a read far less — and folding them together understates a
  *     cache-heavy run badly.
  *   - `reasoningOutputTokens` is the internal-reasoning sub-portion of
  *     `outputTokens` (codex / o-series).
  *   - `apiCalls` is how many model requests the token counts above cover. A
  *     turn that runs a tool makes at least two, and every one of them re-sends
  *     the whole prompt — so it is what turns a prompt size into a bill. `None`
  *     means the backend does not report it, which is not the same as one: an
  *     inferred count would be indistinguishable from a measured one
  *     downstream, so backends that cannot count leave it unset.
  *
  * All three token breakdowns are non-cumulative, so callers can report
  * cache-hit, cache-write and reasoning ratios directly.
  *
  * A new backend must fold cache-read and cache-written tokens INTO
  * `inputTokens` rather than report them alongside it, and leave
  * `cacheWriteInputTokens` at zero when the protocol has no write counter
  * (gemini). The per-backend arithmetic is documented at each driver's
  * `Usage(...)` construction site.
  *
  * Destructure by name (`case Usage(inputTokens = in) =>`): the axis list grows
  * as backends start reporting finer breakdowns, and a positional pattern
  * silently rebinds or stops compiling when it does.
  */
case class Usage(
    inputTokens: Long,
    outputTokens: Long,
    cost: Option[BigDecimal],
    cacheReadInputTokens: Long = 0L,
    reasoningOutputTokens: Long = 0L,
    cacheWriteInputTokens: Long = 0L,
    apiCalls: Option[Long] = None
):
  /** Combine two usages; `cost` and `apiCalls` are `Some` iff at least one side
    * reports them, so a sum that mixes a reporting backend with a silent one
    * under-counts rather than dropping the half that was measured.
    */
  def +(that: Usage): Usage =
    Usage(
      inputTokens = inputTokens + that.inputTokens,
      outputTokens = outputTokens + that.outputTokens,
      cost = (cost ++ that.cost).reduceOption(_ + _),
      cacheReadInputTokens = cacheReadInputTokens + that.cacheReadInputTokens,
      reasoningOutputTokens =
        reasoningOutputTokens + that.reasoningOutputTokens,
      cacheWriteInputTokens =
        cacheWriteInputTokens + that.cacheWriteInputTokens,
      apiCalls = (apiCalls ++ that.apiCalls).reduceOption(_ + _)
    )

object Usage:
  val empty: Usage = Usage(0L, 0L, None, 0L, 0L, 0L, None)
