package orca.tools.claude.mcp

import ox.channels.{BufferCapacity, Channel}
import ox.discard

import java.util.concurrent.atomic.AtomicReference

/** Synchronous rendezvous between the MCP `ask_user` tool handler (a Netty
  * worker thread that needs a string answer to return to the agent) and the
  * host process (the conversation driver that emits `UserQuestion` events and
  * feeds back what the user typed).
  *
  * One queue carries `(question, reply)` pairs from the handler side; each call
  * brings its own private reply channel so concurrent `ask_user` invocations
  * from a busy agent don't cross wires. The handler thread blocks on its reply
  * channel until the host signals it; the host's consumer loop takes from the
  * question queue, surfaces a `UserQuestion`, and calls the `respond` closure
  * with the typed answer.
  *
  * **Closure.** [[close]] errors any still-blocked `reply.receive()` calls and
  * `done`s the question queue, so the drainer loop and Netty workers exit
  * cleanly when the owning conversation ends. The owner
  * ([[orca.tools.claude.ClaudeConversation.onFinalize]]) calls `close` after
  * the conversation's read loop drains — before the MCP server's Netty binding
  * stops, so handlers see the bridge close first.
  */
private[claude] class AskUserBridge(using BufferCapacity):

  private val pending: Channel[(String, Channel[String])] =
    Channel.bufferedDefault

  /** In-flight reply channels (`ask` has sent but not yet received). Tracked so
    * [[close]] can release every Netty worker currently blocked on a reply, not
    * just future ones.
    */
  private val inFlight: AtomicReference[Set[Channel[String]]] =
    new AtomicReference(Set.empty)

  /** Called by the MCP handler. Blocks the calling thread until the host
    * answers via the closure returned by [[take]]. Each call gets its own
    * one-shot reply channel so concurrent invocations stay isolated. Throws
    * [[ChannelClosedException]] if the bridge is closed while blocked — the
    * handler should surface that as a tool error to the agent.
    */
  def ask(question: String): String =
    val reply: Channel[String] = Channel.rendezvous
    val _ = inFlight.updateAndGet(_ + reply)
    try
      pending.send((question, reply))
      reply.receive()
    finally
      val _ = inFlight.updateAndGet(_ - reply)

  /** Called by the host's consumer loop. Returns the next pending question and
    * a closure to deliver the answer. Blocks if no question is queued; throws
    * [[ChannelClosedException]] when the bridge is closed.
    */
  def take(): (String, String => Unit) =
    val (question, reply) = pending.receive()
    (question, answer => reply.send(answer))

  /** Release every thread blocked on this bridge: `done`s all in-flight reply
    * channels (unblocking Netty workers with a [[ChannelClosedException.Done]])
    * and `done`s the pending queue (the drainer loop unwinds with the same).
    * Idempotent; safe to call from a finalizer.
    */
  def close(): Unit =
    val outstanding = inFlight.getAndSet(Set.empty)
    outstanding.foreach(_.doneOrClosed().discard)
    pending.doneOrClosed().discard
