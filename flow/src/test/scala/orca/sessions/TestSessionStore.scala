package orca.sessions

import java.util.concurrent.atomic.AtomicReference

/** An in-memory [[SessionStore]], for tests that run a single attempt and need
  * no event log on disk.
  */
class TestSessionStore extends SessionStore:
  private val held = new AtomicReference[List[SessionRecord]](Nil)

  def records(): List[SessionRecord] = held.get()

  def upsert(record: SessionRecord): Unit =
    val _ = held.updateAndGet(TestSessionStore.upserted(_, record))

private object TestSessionStore:
  def upserted(
      records: List[SessionRecord],
      record: SessionRecord
  ): List[SessionRecord] =
    records.indexWhere(_.key == record.key) match
      case -1 => records :+ record
      case i  => records.updated(i, record)
