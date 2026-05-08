package orca.subprocess

import orca.{
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult,
  OrcaDebug,
  OrcaFlowException,
  OrcaInteractiveCancelled
}

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Base implementation for stream-driven [[Conversation]] drivers.
  *
  * Owns the boilerplate every backend needs to translate an
  * [[orca.subprocess.PipedCliProcess]] into a [[Conversation]]:
  *
  *   - A daemon stdout reader thread that calls [[handleLine]] on each inbound
  *     line and, on EOF or error, finalises the [[Outcome]].
  *   - A daemon stderr drain thread that calls [[handleStderr]] on each line.
  *   - A single-consumer event queue surfaced via [[events]].
  *   - Lifecycle: `awaitResult` joins the reader, returns `Right(LlmResult)` /
  *     `Left(OrcaInteractiveCancelled)` / throws for anything else; `cancel`
  *     SIGINTs the process and lets the reader observe EOF.
  *
  * Backends supply only the protocol-specific bits: [[handleLine]] (parse +
  * translate to events / outcome), the optional [[handleStderr]] override
  * (filter noise, drop the prefix), and the optional [[onFinalize]] hook (drain
  * stderr before the queue closes, etc.).
  *
  * The driver's role is strictly protocol translation: bytes in →
  * [[ConversationEvent]]s out. Rendering and approval-policy live elsewhere.
  * Outbound writes (user turns, tool-approval responses) happen on the
  * channel's thread; the reader thread only produces events.
  */
private[orca] abstract class StreamConversation[B <: Backend](
    process: PipedCliProcess,
    /** Used in thread names ("claude-conversation-reader"), debug traces,
      * parse-error messages, and the default stderr error prefix. Should match
      * the user-facing backend name.
      */
    backendName: String,
    initialPrompt: String = ""
) extends Conversation[B]:

  import StreamConversation.*

  protected val eventQueue: EventQueue = new EventQueue
  protected val outcomeRef: AtomicReference[Option[Outcome[B]]] =
    AtomicReference(None)
  protected val cancelled: AtomicBoolean = new AtomicBoolean(false)

  // Surface the opening prompt to the channel before any agent
  // output. Without this, the channel sits silent while the agent
  // warms up — giving the user nothing to anchor the eventual
  // response against.
  if initialPrompt.nonEmpty then
    eventQueue.enqueue(ConversationEvent.UserMessage(initialPrompt))

  private val readerThread: Thread =
    val t = new Thread(() => readLoop(), s"$backendName-conversation-reader")
    t.setDaemon(true)
    t.start()
    t

  private val stderrThread: Thread =
    val t = new Thread(() => stderrLoop(), s"$backendName-conversation-stderr")
    t.setDaemon(true)
    t.start()
    t

  // --- Conversation surface ---

  def events: Iterator[ConversationEvent] = eventQueue.iterator

  def awaitResult(): Either[OrcaInteractiveCancelled, LlmResult[B]] =
    readerThread.join()
    outcomeRef.get() match
      case Some(Outcome.Success(r))  => Right(r)
      case Some(Outcome.Cancelled()) => Left(new OrcaInteractiveCancelled())
      case Some(Outcome.Failed(e))   => throw e
      case None =>
        throw new OrcaFlowException(
          s"$backendName interactive session ended without producing a result"
        )

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      process.sendSigInt()
      // The reader loop sees EOF on stdoutLines and finalises the
      // outcome on its own — no need to touch the queue from here.

  // --- Hooks for backend implementations ---

  /** Process a single line of stdout. Implementations parse the protocol
    * message and translate to [[ConversationEvent]] enqueues (and/or
    * [[outcomeRef]] updates). Exceptions thrown here are caught by the base
    * loop and surfaced as a generic parse-error Error event — backends don't
    * need their own try/catch.
    */
  protected def handleLine(line: String): Unit

  /** Process one line of stderr. Default: enqueue as an Error event with the
    * backend name as a prefix. Override to filter known- noise lines or to
    * apply different formatting.
    */
  protected def handleStderr(line: String): Unit =
    if line.trim.nonEmpty then
      eventQueue.enqueue(ConversationEvent.Error(s"$backendName: $line"))

  /** Hook called inside `finalizeLoop` after the outcome is settled but before
    * the event queue closes. Default: no-op. Backends use it for
    * trailing-stderr drains (codex's bounded join), final housekeeping events,
    * etc.
    */
  protected def onFinalize(): Unit = ()

  /** The exception used when the subprocess exits with code 0 without having
    * sent a terminal protocol message. Default: a generic `OrcaFlowException`.
    * Backends may override with a more specific message.
    */
  protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      s"$backendName exited cleanly but never sent a terminal message"
    )

  // --- Internals ---

  private def readLoop(): Unit =
    try
      for line <- process.stdoutLines do
        debugLog("stdout", line)
        if !cancelled.get() then
          try handleLine(line)
          catch
            case e: Exception =>
              eventQueue.enqueue(
                ConversationEvent.Error(
                  s"Failed to parse $backendName line: ${e.getMessage}"
                )
              )
    catch
      case NonFatal(e) =>
        debugLog("stdout-error", e.toString)
        val _ = outcomeRef.compareAndSet(None, Some(Outcome.failed[B](e)))
    finally finalizeLoop()

  private def stderrLoop(): Unit =
    try
      for line <- process.stderrLines do
        debugLog("stderr", line)
        handleStderr(line)
    catch
      case NonFatal(t) =>
        // stderr draining is best-effort — the main thread doesn't
        // depend on it. Surface the swallowed throwable under
        // ORCA_DEBUG so a real bug isn't masked.
        debugLog("stderr-error", s"${t.getClass.getName}: ${t.getMessage}")

  protected def debugLog(channel: String, line: String): Unit =
    if OrcaDebug.streamTrace then
      System.err.println(s"[orca-debug $backendName-$channel] $line")

  /** Access to the stderr-drain thread for backends whose [[onFinalize]] needs
    * to wait for stderr to flush.
    */
  protected def stderrDrainThread: Thread = stderrThread

  private def finalizeLoop(): Unit =
    val finalOutcome: Outcome[B] = outcomeRef.get() match
      case Some(existing)          => existing
      case None if cancelled.get() => Outcome.cancelled[B]
      case None                    => outcomeFromExit(process.tryExitCode)
    val _ = outcomeRef.compareAndSet(None, Some(finalOutcome))
    onFinalize()
    eventQueue.close()

  private def outcomeFromExit(exitCode: Option[Int]): Outcome[B] =
    exitCode match
      case Some(0) => Outcome.failed[B](cleanExitWithoutResult())
      case Some(code) =>
        Outcome.failed[B](
          new OrcaFlowException(s"$backendName exited with code $code")
        )
      case None => Outcome.cancelled[B]

private[orca] object StreamConversation:

  /** Internal outcome of the session as the reader sees it. Modelled as a
    * sealed trait + cases (rather than an `enum`) because `Cancelled` and
    * `Failed` are backend-agnostic — using `Nothing` as their `B` makes them
    * assignable to any `Outcome[B]` while the pattern match still narrows
    * `Success`'s `B` correctly.
    *
    * `Outcome` is invariant in `B` because `LlmResult[B]` is invariant (the
    * phantom `B` in `SessionId[B]` etc. is meant to be exact); `Cancelled` and
    * `Failed` get a wide-bounded `Outcome[B]` via the `Outcome.cancelled` /
    * `Outcome.failed` smart constructors below.
    */
  sealed trait Outcome[B <: Backend]
  object Outcome:
    final case class Success[B <: Backend](result: LlmResult[B])
        extends Outcome[B]
    final case class Cancelled[B <: Backend]() extends Outcome[B]
    final case class Failed[B <: Backend](error: Throwable) extends Outcome[B]

    def cancelled[B <: Backend]: Outcome[B] = Cancelled[B]()
    def failed[B <: Backend](error: Throwable): Outcome[B] = Failed[B](error)

  /** Blocking queue + single-consumer iterator. `close()` signals end-of-stream
    * to whichever thread is iterating.
    */
  final class EventQueue:
    private val queue = new LinkedBlockingQueue[Option[ConversationEvent]]()

    def enqueue(event: ConversationEvent): Unit =
      val _ = queue.offer(Some(event))

    def close(): Unit =
      val _ = queue.offer(None)

    val iterator: Iterator[ConversationEvent] = new Iterator[ConversationEvent]:
      // Single-consumer per the Conversation contract; a plain `var`
      // with a null sentinel is enough. The three states are:
      //   null      — nothing peeked yet (will block on next `hasNext`)
      //   Some(e)   — peeked event, returned by next `next()`
      //   None      — stream closed, `hasNext` stays false forever
      private var peeked: Option[ConversationEvent] = null

      def hasNext: Boolean =
        if peeked == null then peeked = queue.take()
        peeked.isDefined

      def next(): ConversationEvent =
        if !hasNext then throw new NoSuchElementException("event stream closed")
        val value = peeked.get
        peeked = null
        value
