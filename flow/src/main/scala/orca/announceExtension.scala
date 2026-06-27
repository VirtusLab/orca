package orca

import orca.events.OrcaEvent
import orca.agents.Announce

/** `value.announce` — manually emit an [[Announce]] message as a `Step`. */
extension [O](value: O)(using a: Announce[O])
  def announce(using ctx: FlowContext): Unit =
    a.message(value).foreach(msg => ctx.emit(OrcaEvent.Step(msg)))
