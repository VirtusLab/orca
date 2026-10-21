package orca.events

import orca.safe.{Logger, Ref}

import orca.safe.prelude.NonFatal

/** Synchronously forwards every `OrcaEvent` to a fixed list of listeners in
  * registration order. Implements `OrcaListener` itself, so it can be passed
  * anywhere a single listener is expected.
  *
  * Total: a throwing listener is logged at ERROR with its stack, announced once
  * on stderr, then quarantined — permanently excluded from further dispatch for
  * the rest of the run. The remaining listeners still see every event and the
  * emitter is never disturbed, so observation never alters flow control.
  *
  * Thread-safe: holds only an immutable list. Concurrent `onEvent` calls fan
  * out concurrently to each listener — the listeners must themselves be
  * thread-safe (see [[OrcaListener]]).
  */
class EventDispatcher(listeners: List[OrcaListener]) extends OrcaListener:
  private val log = Logger(classOf[EventDispatcher].getName)

  // Listeners that threw, disabled for the rest of the run. Emitters include
  // reviewer forks, so it is shared across threads; an actor would serialise
  // every event through one thread to guard a set that rarely changes.
  private val quarantined: Ref[Set[OrcaListener]] = Ref(Set.empty)

  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach: l =>
      if !quarantined.get.contains(l) then
        try l.onEvent(event)
        catch
          case NonFatal(e) =>
            // False when another thread already quarantined `l`; gating on it
            // makes the announcement fire exactly once.
            if quarantined.modify(q => (q + l, !q.contains(l))) then
              log.error(
                s"listener ${l.getClass.getName} failed on ${event.getClass.getSimpleName}" +
                  " — listener disabled for the rest of the run",
                e
              )
              // The ERROR above lands in the trace file only (the `orca` logger
              // is file-only once OrcaLog.start() runs), so also write a direct
              // stderr line — otherwise a broken terminal listener throwing on
              // the very Error event carrying the user's failure would leave
              // nothing visible. Fold an Error event's message in so that
              // failure isn't lost. Raw stderr may tear the status row, but this
              // path fires only when a listener is broken.
              val payload = event match
                case OrcaEvent.Error(message, _) => s" (event error: $message)"
                case _                           => ""
              Logger.stderr(
                s"[orca] listener ${l.getClass.getName} failed on " +
                  s"${event.getClass.getSimpleName}: ${e.getMessage}$payload (listener disabled)"
              )
