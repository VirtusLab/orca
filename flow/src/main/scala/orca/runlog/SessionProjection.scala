package orca.runlog

import orca.sessions.SessionRecord

/** The run's durable-session records, as the event log describes them. */
private[orca] object SessionProjection:

  /** The records written since the last [[RunEvent.RunSucceeded]], one per
    * [[orca.agents.SessionKey]] in the order the keys were first minted. A
    * later [[RunEvent.SessionMinted]] for a key replaces its record in place; a
    * [[RunEvent.SessionWireId]] sets the resume wire id of the record with that
    * id, and is ignored when there is none.
    */
  def records(events: List[RunEvent]): List[SessionRecord] =
    events.foldLeft(List.empty[SessionRecord])(applied)

  /** `records` after `event`, by the rules of [[records]]. */
  def applied(
      records: List[SessionRecord],
      event: RunEvent
  ): List[SessionRecord] =
    event match
      case _: RunEvent.RunSucceeded => Nil
      case m: RunEvent.SessionMinted =>
        val record = SessionRecord(
          name = m.name,
          stage = m.stage,
          id = m.id,
          seed = m.seed,
          resumeWireId = None,
          backend = m.backend
        )
        records.indexWhere(_.key == record.key) match
          case -1 => records :+ record
          case i  => records.updated(i, record)
      case w: RunEvent.SessionWireId =>
        records.map: r =>
          if r.id == w.id then r.copy(resumeWireId = Some(w.wireId)) else r
      case _ => records
