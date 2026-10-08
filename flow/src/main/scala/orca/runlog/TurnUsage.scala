package orca.runlog

import orca.events.Usage

/** The token axes of [[orca.events.Usage]] as a [[RunEvent.Turn]] records them,
  * under `Usage`'s field names. The input axes are disjoint, so the total
  * prompt is their sum.
  *
  * `Usage.apiCalls` sits on the turn itself. `Usage.cost` is not carried: it is
  * only the portion backends reported, while the turn's resolved
  * [[orca.events.Cost]] says whether it was reported or estimated.
  */
private[orca] case class TurnUsage(
    freshInputTokens: Long,
    cacheReadInputTokens: Long,
    cacheWriteInputTokens: Long,
    outputTokens: Long,
    reasoningOutputTokens: Long
)

private[orca] object TurnUsage:
  def of(usage: Usage): TurnUsage = TurnUsage(
    freshInputTokens = usage.freshInputTokens,
    cacheReadInputTokens = usage.cacheReadInputTokens,
    cacheWriteInputTokens = usage.cacheWriteInputTokens,
    outputTokens = usage.outputTokens,
    reasoningOutputTokens = usage.reasoningOutputTokens
  )
