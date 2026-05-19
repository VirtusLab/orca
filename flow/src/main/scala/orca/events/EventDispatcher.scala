package orca.events

/** Synchronously forwards every `OrcaEvent` to a fixed list of listeners in
  * registration order. Listener exceptions propagate — an observation layer
  * that throws is almost certainly a bug, and silent swallowing would hide it
  * from the flow author.
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
  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach(_.onEvent(event))
