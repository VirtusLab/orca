package orca.tools.claude.mcp

import ox.channels.{BufferCapacity, Channel}

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
  * Lifecycle: created per [[orca.backend.Conversation]]. The consumer fork
  * lives in the same supervised scope as the conversation; scope-end interrupts
  * both [[ask]] and [[take]] callers via Ox's standard channel cancellation.
  */
private[claude] class AskUserBridge(using BufferCapacity):

  private val pending: Channel[(String, Channel[String])] =
    Channel.bufferedDefault

  /** Called by the MCP handler. Blocks the calling thread until the host
    * answers via the closure returned by [[take]]. Each call gets its own
    * one-shot reply channel so concurrent invocations stay isolated.
    */
  def ask(question: String): String =
    val reply: Channel[String] = Channel.rendezvous
    pending.send((question, reply))
    reply.receive()

  /** Called by the host's consumer loop. Returns the next pending question and
    * a closure to deliver the answer. Blocks if no question is queued.
    */
  def take(): (String, String => Unit) =
    val (question, reply) = pending.receive()
    (question, answer => reply.send(answer))
