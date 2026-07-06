package orca.events

import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/** Synchronously forwards every `OrcaEvent` to a fixed list of listeners in
  * registration order.
  *
  * Implements `OrcaListener` itself so it composes naturally: a tool that
  * accepts an `OrcaListener` can take a dispatcher directly, and dispatchers
  * could be nested if a future use case warranted it.
  *
  * Thread-safe: holds only an immutable list and delegates to each listener in
  * turn. Concurrent `onEvent` calls fan out concurrently to each listener — the
  * listeners must themselves be thread-safe (see [[OrcaListener]]).
  */
class EventDispatcher(listeners: List[OrcaListener]) extends OrcaListener:
  private val log = LoggerFactory.getLogger(classOf[EventDispatcher])

  /** Total: a listener that throws is a legitimate error, so it is logged at
    * ERROR with its full stack (lands in the trace file only, since
    * `OrcaLog.start()` makes the `orca` logger non-additive/file-only) and ALSO
    * announced on stderr as a single high-level line, then skipped; the
    * remaining listeners still see the event and the emitter is never
    * disturbed. Observation must not alter flow control — stage bookkeeping
    * used to depend on emit-placement relative to try blocks to defend against
    * throwing listeners; making emit total retires that whole class of ordering
    * constraints.
    */
  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach: l =>
      try l.onEvent(event)
      catch
        case NonFatal(e) =>
          log.error(
            s"listener ${l.getClass.getName} failed on ${event.getClass.getSimpleName}",
            e
          )
          // The ERROR above (with its stack) lands in the trace file only (the
          // `orca` logger is file-only once OrcaLog.start() runs), so ALSO
          // write a direct stderr line — otherwise the pathological case (a
          // broken terminal listener throwing on the very Error event that
          // carries the user's failure) would leave nothing visible on the
          // console. When the event is an Error, fold its message payload in
          // so that underlying failure isn't lost. Raw stderr may tear the
          // terminal's status row, but this path fires only when a listener —
          // possibly the terminal itself — is broken; visibility beats tidiness
          // here.
          val payload = event match
            case OrcaEvent.Error(message) => s" (event error: $message)"
            case _                        => ""
          System.err.println(
            s"[orca] listener ${l.getClass.getName} failed on " +
              s"${event.getClass.getSimpleName}: ${e.getMessage}$payload"
          )
