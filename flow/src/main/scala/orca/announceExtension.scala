package orca

import orca.events.{Announcement, OrcaEvent}
import orca.agents.Announce

/** `value.announce` — manually emit an [[orca.agents.Announce]] message as a
  * `Step`.
  */
extension [O](value: O)(using a: Announce[O])
  def announce(using ctx: FlowContext): Unit =
    Announce.announcement(a, value) match
      case Announcement.Say(msg) => ctx.emit(OrcaEvent.Step(msg))
      case Announcement.Silent | Announcement.Unannounced => ()
