package orca.testkit

import orca.events.Usage

/** Builds a [[Usage]] from the axes a test cares about, leaving the others at
  * zero.
  *
  * `input` is the whole prompt, cache axes included — the way a cost summary
  * reads it, and the reason the cache arguments can be raised without also
  * raising `input`.
  */
object Usages:
  def usage(
      input: Long,
      output: Long,
      cost: Option[BigDecimal] = None,
      cacheRead: Long = 0L,
      cacheWrite: Long = 0L,
      reasoning: Long = 0L,
      apiCalls: Option[Long] = None
  ): Usage =
    Usage.inclusiveInput(
      totalInputTokens = input,
      cacheReadInputTokens = cacheRead,
      cacheWriteInputTokens = cacheWrite,
      outputTokens = output,
      reasoningOutputTokens = reasoning,
      cost = cost,
      apiCalls = apiCalls,
      wireTotal = None
    )
