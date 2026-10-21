package orca.backend.mcp

import orca.safe.concurrency
import orca.safe.prelude.discard
import ox.channels.{BufferCapacity, Channel}

/** Synchronous rendezvous between the MCP `ask_user` tool handler (which needs
  * a string answer to return to the agent) and the host process (the
  * [[orca.backend.DecodedTurn]] that emits `UserQuestion` events and feeds back
  * what the user typed).
  *
  * One queue carries `(question, reply)` pairs from the handler side; each call
  * brings its own private reply channel so concurrent `ask_user` invocations
  * don't cross wires. The handler blocks on its reply channel until the host's
  * consumer loop takes from the queue, surfaces a `UserQuestion`, and calls
  * `respond` with the typed answer.
  *
  * Both sides block on interruptible channel operations, and both run as forks
  * of the turn scope (the drainer, and the MCP server's request handlers), so
  * the scope's end unblocks them.
  */
private[backend] class AskUserBridge(using BufferCapacity):

  private val pending: Channel[(String, Channel[String])] =
    concurrency.bufferedDefaultChannel

  /** Called by the MCP handler. Blocks the calling thread until the host
    * answers. Each call gets its own one-shot reply channel so concurrent
    * invocations stay isolated.
    *
    * The reply channel is always `done`'d on exit. Otherwise a handler that
    * exits early (e.g. the HTTP client aborts after its own timeout) would
    * leave the rendezvous dangling, and the renderer's later `respond(answer)`
    * would block forever on a `send` with no receiver; the `done` makes that
    * `send` raise a recoverable `ChannelClosedException` instead.
    */
  def ask(question: String): String =
    val reply: Channel[String] = concurrency.rendezvousChannel
    try
      pending.send((question, reply))
      reply.receive()
    finally reply.doneOrClosed().discard

  /** Called by the host's consumer loop. Returns the next pending question and
    * the closure that delivers the answer to the originating [[ask]]. Blocks if
    * no question is queued.
    *
    * A second call of the returned `respond` after the first returns is a
    * no-op, protecting against a renderer that double-fires: `respond` `done`s
    * the reply channel after sending. `sendOrClosed` also makes a send after
    * the originating `ask` has exited a no-op rather than an exception escaping
    * into the renderer's dispatch loop.
    */
  def nextQuestion(): PendingQuestion =
    val (question, reply) = pending.receive()
    val respond: String -> Unit = answer =>
      reply.sendOrClosed(answer).discard
      reply.doneOrClosed().discard
    PendingQuestion(question, respond)

/** Single pending invocation of `ask_user`: the question text the agent
  * supplied, plus a closure that delivers the user's typed answer back to the
  * blocked MCP handler.
  */
private[mcp] final case class PendingQuestion(
    question: String,
    respond: String -> Unit
)
