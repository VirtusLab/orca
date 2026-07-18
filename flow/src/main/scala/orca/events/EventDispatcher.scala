package orca.events

import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

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
  private val log = LoggerFactory.getLogger(classOf[EventDispatcher])

  // Listeners that threw, disabled for the rest of the run. Cross-thread
  // (emitters include reviewer forks), hence a concurrent set.
  private val quarantined =
    java.util.concurrent.ConcurrentHashMap.newKeySet[OrcaListener]()

  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach: l =>
      if !quarantined.contains(l) then
        try l.onEvent(event)
        catch
          case NonFatal(e) =>
            // `add` returns false if another thread already quarantined `l`;
            // gating on it makes the announcement fire exactly once.
            if quarantined.add(l) then
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
                case OrcaEvent.Error(message) => s" (event error: $message)"
                case _                        => ""
              System.err.println(
                s"[orca] listener ${l.getClass.getName} failed on " +
                  s"${event.getClass.getSimpleName}: ${e.getMessage}$payload (listener disabled)"
              )
