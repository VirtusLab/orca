package orca.events

import orca.agents.Model

/** What a turn that failed after the model ran had spent by the time it failed,
  * as the driver that saw the wire observed it.
  *
  * `Unobserved` is a claim, not a fallback: the protocol gave this driver
  * nothing to report on its failure frame (codex — ADR 0007 puts usage on
  * `turn.completed` only). It emits no `TokensUsed`, because an all-zero event
  * would be indistinguishable from a measured zero.
  */
enum TurnDebit:
  case Observed(usage: Usage, model: Option[Model])
  case Unobserved
