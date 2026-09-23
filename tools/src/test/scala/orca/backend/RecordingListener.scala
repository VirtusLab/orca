package orca.backend

import orca.events.{OrcaEvent, OrcaListener}

import java.util.concurrent.atomic.AtomicReference

/** Records every `OrcaEvent` it sees so tests can assert on the emission order
  * without scaffolding a full listener.
  */
private class RecordingListener extends OrcaListener:
  private val log = new AtomicReference[List[OrcaEvent]](Nil)
  def events: List[OrcaEvent] = log.get().reverse
  def onEvent(event: OrcaEvent): Unit =
    val _ = log.updateAndGet(event :: _)
