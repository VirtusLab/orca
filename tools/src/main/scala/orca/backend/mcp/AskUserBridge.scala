package orca.backend.mcp

import ox.channels.{BufferCapacity, Channel}
import ox.discard

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** Synchronous rendezvous between the MCP `ask_user` tool handler (a Netty
  * worker thread that needs a string answer to return to the agent) and the
  * host process (the conversation driver that emits `UserQuestion` events and
  * feeds back what the user typed).
  *
  * One queue carries `(question, reply)` pairs from the handler side; each call
  * brings its own private reply channel so concurrent `ask_user` invocations
  * don't cross wires. The handler thread blocks on its reply channel until the
  * host's consumer loop takes from the queue, surfaces a `UserQuestion`, and
  * calls `respond` with the typed answer.
  *
  * [[close]] errors any still-blocked `reply.receive()` calls and `done`s the
  * question queue so the drainer loop and Netty workers exit cleanly. The owner
  * calls `close` after the conversation's read loop drains — before the MCP
  * server's Netty binding stops, so handlers see the bridge close first.
  */
private[orca] class AskUserBridge(using BufferCapacity):

  private val pending: Channel[(String, Channel[String])] =
    Channel.bufferedDefault

  /** In-flight reply channels (`ask` has sent but not yet received). Tracked so
    * [[close]] can release every Netty worker currently blocked on a reply, not
    * just future ones.
    */
  private val inFlight: AtomicReference[Set[Channel[String]]] =
    new AtomicReference(Set.empty)

  /** Called by the MCP handler. Blocks the calling thread until the host
    * answers. Each call gets its own one-shot reply channel so concurrent
    * invocations stay isolated. Throws [[ChannelClosedException]] if the bridge
    * is closed while blocked — the handler surfaces that as a tool error.
    *
    * The reply channel is always `done`'d on exit. Otherwise a handler that
    * exits early (e.g. the HTTP client aborts after its own timeout) would
    * leave the rendezvous dangling, and the renderer's later `respond(answer)`
    * would block forever on a `send` with no receiver; the `done` makes that
    * `send` raise a recoverable `ChannelClosedException` instead.
    */
  def ask(question: String): String =
    val reply: Channel[String] = Channel.rendezvous
    val _ = inFlight.updateAndGet(_ + reply)
    try
      pending.send((question, reply))
      reply.receive()
    finally
      val _ = inFlight.updateAndGet(_ - reply)
      reply.doneOrClosed().discard

  /** Called by the host's consumer loop. Returns the next pending question and
    * the closure that delivers the answer to the originating [[ask]]. Blocks if
    * no question is queued; throws [[ChannelClosedException]] when the bridge
    * is closed.
    *
    * The returned `respond` closure is idempotent (only the first call
    * delivers) to protect against a renderer that double-fires. It uses
    * `sendOrClosed` so that if the originating `ask` has already exited (its
    * reply channel `done`'d), the caller sees a no-op rather than an exception
    * escaping into the renderer's dispatch loop.
    */
  def nextQuestion(): PendingQuestion =
    val (question, reply) = pending.receive()
    val delivered = new AtomicBoolean(false)
    val respond: String => Unit = answer =>
      if delivered.compareAndSet(false, true) then
        reply.sendOrClosed(answer).discard
    PendingQuestion(question, respond)

  /** Release every thread blocked on this bridge: `done`s all in-flight reply
    * channels (unblocking Netty workers with a [[ChannelClosedException.Done]])
    * and `done`s the pending queue (the drainer loop unwinds with the same).
    * Idempotent; safe to call from a finalizer.
    */
  def close(): Unit =
    val outstanding = inFlight.getAndSet(Set.empty)
    outstanding.foreach(_.doneOrClosed().discard)
    pending.doneOrClosed().discard

/** Single pending invocation of `ask_user`: the question text the agent
  * supplied, plus a closure that delivers the user's typed answer back to the
  * blocked MCP handler.
  */
private[mcp] final case class PendingQuestion(
    question: String,
    respond: String => Unit
)
