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

  /** Total: a listener that throws is logged (WARN — visible on the console)
    * and skipped; the remaining listeners still see the event and the emitter
    * is never disturbed. Observation must not alter flow control — stage
    * bookkeeping used to depend on emit-placement relative to try blocks to
    * defend against throwing listeners; making emit total retires that whole
    * class of ordering constraints.
    */
  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach: l =>
      try l.onEvent(event)
      catch
        case NonFatal(e) =>
          log.warn(
            s"listener ${l.getClass.getName} failed on ${event.getClass.getSimpleName}",
            e
          )
