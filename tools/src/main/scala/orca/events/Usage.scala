package orca.events

import org.slf4j.LoggerFactory

/** Token + cost accounting for one or more LLM calls.
  *
  * The prompt is stored as three disjoint parts — `freshInputTokens`,
  * `cacheReadInputTokens` (served FROM the prompt cache),
  * `cacheWriteInputTokens` (written INTO it; Anthropic spells this "cache
  * creation") — and [[Usage.inputTokens]] is their sum. They are separate axes
  * because they bill at opposite ends of base input: a write costs more, a read
  * far less, and folding them together understates a cache-heavy run badly.
  * `reasoningOutputTokens` is the internal-reasoning sub-portion of
  * `outputTokens` (codex / o-series), so it is the one axis that is NOT
  * disjoint from its sibling.
  *
  * `apiCalls` is how many model requests the token counts above cover. A turn
  * that runs a tool makes at least two, and every one of them re-sends the
  * whole prompt — so it is what turns a prompt size into a bill. `None` means
  * the backend does not report it, which is not the same as one: an inferred
  * count would be indistinguishable from a measured one downstream, so backends
  * that cannot count leave it unset.
  *
  * A wire whose input counter EXCLUDES the cache categories (claude, pi,
  * opencode) maps straight onto this constructor; one that INCLUDES them
  * (codex, gemini) goes through [[Usage.inclusiveInput]], which splits it.
  *
  * Construct and destructure by name (`Usage(outputTokens = 3L, …)`): the axis
  * list grows as backends start reporting finer breakdowns, and a positional
  * argument or pattern silently rebinds when it does — invisibly, since every
  * axis is money.
  */
case class Usage(
    freshInputTokens: Long,
    cacheReadInputTokens: Long,
    cacheWriteInputTokens: Long,
    outputTokens: Long,
    reasoningOutputTokens: Long,
    cost: Option[BigDecimal],
    apiCalls: Option[Long]
):
  /** Total prompt tokens, cache categories included. */
  def inputTokens: Long =
    freshInputTokens + cacheReadInputTokens + cacheWriteInputTokens

  /** Whether this turn spent anything a price list could bill. */
  def spentTokens: Boolean = inputTokens > 0 || outputTokens > 0

  /** Combine two usages; `cost` and `apiCalls` are `Some` iff at least one side
    * reports them, so a sum that mixes a reporting backend with a silent one
    * under-counts rather than dropping the half that was measured.
    */
  def +(that: Usage): Usage =
    Usage(
      freshInputTokens = freshInputTokens + that.freshInputTokens,
      cacheReadInputTokens = cacheReadInputTokens + that.cacheReadInputTokens,
      cacheWriteInputTokens =
        cacheWriteInputTokens + that.cacheWriteInputTokens,
      outputTokens = outputTokens + that.outputTokens,
      reasoningOutputTokens =
        reasoningOutputTokens + that.reasoningOutputTokens,
      cost = (cost ++ that.cost).reduceOption(_ + _),
      apiCalls = (apiCalls ++ that.apiCalls).reduceOption(_ + _)
    )

object Usage:
  private val log = LoggerFactory.getLogger("orca.events")

  val empty: Usage = Usage(
    freshInputTokens = 0L,
    cacheReadInputTokens = 0L,
    cacheWriteInputTokens = 0L,
    outputTokens = 0L,
    reasoningOutputTokens = 0L,
    cost = None,
    apiCalls = None
  )

  /** Build a usage from a wire whose input counter INCLUDES the cache
    * categories (codex, gemini): the fresh part is the remainder, clamped at
    * zero so a backend over-reporting a cache axis can't produce a negative
    * charge.
    *
    * `wireTotal` is the backend's own all-token total where it reports one. A
    * non-zero residue against the axes decoded here means the wire carries a
    * counter this decoder drops — a renamed or newly added one announces itself
    * in the debug log instead of vanishing into a smaller bill.
    */
  def inclusiveInput(
      totalInputTokens: Long,
      cacheReadInputTokens: Long,
      cacheWriteInputTokens: Long,
      outputTokens: Long,
      reasoningOutputTokens: Long,
      cost: Option[BigDecimal],
      apiCalls: Option[Long],
      wireTotal: Option[Long]
  ): Usage =
    val usage = Usage(
      freshInputTokens =
        (totalInputTokens - cacheReadInputTokens - cacheWriteInputTokens) max 0L,
      cacheReadInputTokens = cacheReadInputTokens,
      cacheWriteInputTokens = cacheWriteInputTokens,
      outputTokens = outputTokens,
      reasoningOutputTokens = reasoningOutputTokens,
      cost = cost,
      apiCalls = apiCalls
    )
    wireTotal.foreach: total =>
      val decoded = usage.inputTokens + usage.outputTokens
      if total != decoded then
        log.debug(
          s"Usage residue ${total - decoded}: the wire reports $total " +
            s"tokens, the decoded axes sum to $decoded"
        )
    usage
