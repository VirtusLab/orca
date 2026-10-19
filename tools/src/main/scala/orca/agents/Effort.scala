package orca.agents

/** How much reasoning an agent spends per turn. Set with
  * `claude.sonnet.withEffort(Effort.Medium)`; only harnesses that expose the
  * knob offer `withEffort` (today: claude).
  */
enum Effort(val name: String):
  case Low extends Effort("low")
  case Medium extends Effort("medium")
  case High extends Effort("high")
  case XHigh extends Effort("xhigh")
  case Max extends Effort("max")
