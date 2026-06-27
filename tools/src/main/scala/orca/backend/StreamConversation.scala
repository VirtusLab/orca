package orca.backend

import orca.backend.mcp.{AskUserBridge, AskUserSession}
import orca.agents.BackendTag
import orca.util.OrcaDebug
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Base implementation for stream-driven [[Conversation]] drivers.
  *
  * Owns the boilerplate every backend needs to translate a [[StreamSource]] (a
  * subprocess's stdout, or an SSE connection) into a [[Conversation]]:
  *
  *   - A daemon reader thread that calls [[handleLine]] on each inbound line
  *     and, on EOF or error, finalises the [[Outcome]].
  *   - A daemon diagnostic drain thread that calls [[handleStderr]] on each
  *     [[StreamSource.errorLines]] line.
  *   - A single-consumer event queue surfaced via [[events]].
  *   - Lifecycle: `awaitResult` joins the reader, returns `Right(AgentResult)`
  *     / `Left(OrcaInteractiveCancelled)` / throws for anything else; `cancel`
  *     interrupts the source and lets the reader observe EOF.
  *
  * Backends supply only the protocol-specific bits: [[handleLine]] (parse +
  * translate to events / outcome), the optional [[handleStderr]] override
  * (filter noise, drop the prefix), and the optional [[onFinalize]] hook (drain
  * the diagnostic stream before the queue closes, etc.).
  *
  * The driver's role is strictly protocol translation: bytes in →
  * [[ConversationEvent]]s out. Rendering and approval-policy live elsewhere.
  * Outbound writes (user turns, tool-approval responses) happen on the
  * channel's thread; the reader thread only produces events.
  */
private[orca] abstract class StreamConversation[B <: BackendTag](
    source: StreamSource,
    /** Used in thread names ("claude-conversation-reader"), debug traces,
      * parse-error messages, and the default stderr error prefix. Should match
      * the user-facing backend name.
      */
    backendName: String,
    initialPrompt: String = "",
    /** Set by backends that answer `ask_user` through their own protocol
      * (OpenCode, via a native HTTP `question` event). It only affects what
      * [[canAskUser]] reports — the MCP drainer is gated on [[askUser]] alone.
      */
    nativeAskUser: Boolean = false
) extends Conversation[B]:

  import StreamConversation.*

  protected val eventQueue: EventQueue = new EventQueue
  protected val outcomeRef: AtomicReference[Option[Outcome[B]]] =
    AtomicReference(None)
  protected val cancelled: AtomicBoolean = new AtomicBoolean(false)

  /** Optional `ask_user` MCP resource bundle for this conversation. Subclasses
    * on interactive calls override (via `override val askUser` on the ctor
    * param) to point the base at the bundle; the base spawns the drainer thread
    * inside [[start]] and closes the bundle in [[finalizeLoop]]
    * post-`onFinalize`. Autonomous calls leave the default `None` and the
    * wiring is a no-op.
    */
  protected def askUser: Option[AskUserSession] = None

  /** True iff an `ask_user` MCP bundle is wired (claude/codex) or the backend
    * declared its own ask-user channel via [[nativeAskUser]] (OpenCode).
    */
  final def canAskUser: Boolean = nativeAskUser || askUser.isDefined

  // Surface the opening prompt to the channel before any agent
  // output. Without this, the channel sits silent while the agent
  // warms up — giving the user nothing to anchor the eventual
  // response against.
  if initialPrompt.nonEmpty then
    eventQueue.enqueue(ConversationEvent.UserMessage(initialPrompt))

  // Lazy so the worker threads only spin up once the subclass has
  // finished initialising its own fields — otherwise a pre-populated
  // stdout fake (think tests) can let the reader race past EOF and
  // touch a `null` subclass val before its `val` initializer runs.
  // Concrete drivers call `start()` at the end of their constructor.
  // Virtual threads: these workers spend their lives blocked on I/O (the stdout
  // read loop, the stderr drain), which is exactly what virtual threads are for
  // — cheap to spawn and they don't pin a platform thread while parked. They are
  // always daemon, so no `setDaemon` call is needed (and none is allowed).
  private lazy val readerThread: Thread =
    Thread
      .ofVirtual()
      .name(s"$backendName-conversation-reader")
      .unstarted(() => readLoop())

  private lazy val stderrThread: Thread =
    Thread
      .ofVirtual()
      .name(s"$backendName-conversation-stderr")
      .unstarted(() => stderrLoop())

  /** Spin up the stdout + stderr workers. Subclasses **must** call this at the
    * end of their constructor, after their own fields are assigned — forgetting
    * it leaves `awaitResult` returning immediately with "interactive session
    * ended without producing a result" because `Thread.join` on a never-started
    * thread is a no-op. The [[ensureStarted]] guard at every public entry point
    * shouts loudly when a subclass forgets.
    */
  protected def start(): Unit =
    // Stderr first so a synchronous-finishing reader (pre-populated fake
    // queues, etc.) still sees a non-null `stderrThread` if it reaches
    // `onFinalize` immediately.
    stderrThread.start()
    readerThread.start()
    // Drainer spawn lands here so the subclass's `askUser` override has
    // already initialized by the time we read it.
    askUser.foreach(r => startAskUserDrainer(r.bridge))

  /** Spin up a daemon thread that bridges an [[AskUserBridge]] into the
    * conversation's event stream: each pending question becomes a
    * `ConversationEvent.UserQuestion` whose `respond` closure delivers the
    * user's typed answer back to the blocked MCP handler. Called from [[start]]
    * for every conversation whose [[askUser]] is `Some`; the thread exits
    * cleanly when `bridge.close()` raises `ChannelClosedException` from
    * `nextQuestion()` — driven by [[finalizeLoop]].
    */
  private def startAskUserDrainer(bridge: AskUserBridge): Unit =
    val t = new Thread(
      () =>
        try
          while !Thread.currentThread().isInterrupted do
            val q = bridge.nextQuestion()
            eventQueue.enqueue(
              ConversationEvent.UserQuestion(q.question, q.respond)
            )
        catch
          // Bridge closure surfaces as ChannelClosedException; exit
          // quietly. NonFatal so an InterruptedException still propagates
          // if something else interrupts the thread.
          case NonFatal(_) => (),
      s"$backendName-conversation-ask-user"
    )
    t.setDaemon(true)
    t.start()

  /** Fail loudly if a subclass constructor reached one of the public methods
    * without calling [[start]] — the symptom would otherwise be a silent
    * "session ended without producing a result". Cheap NEW-state probe.
    */
  private def ensureStarted(label: String): Unit =
    if readerThread.getState == Thread.State.NEW then
      throw new IllegalStateException(
        s"$backendName conversation: $label called before start() — " +
          "subclass constructor likely forgot to call start() at the end."
      )

  // --- Conversation surface ---

  def events: Iterator[ConversationEvent] =
    ensureStarted("events")
    eventQueue.iterator

  def awaitResult(): Either[OrcaInteractiveCancelled, AgentResult[B]] =
    ensureStarted("awaitResult")
    readerThread.join()
    outcomeRef.get() match
      case Some(Outcome.Success(r))  => Right(r)
      case Some(Outcome.Cancelled()) => Left(new OrcaInteractiveCancelled())
      // A failure here means the turn ran (the session is registered) and then
      // errored — tag it non-retryable so the autonomous retry loop doesn't
      // reopen the locked session id. See [[AgentTurnFailed]].
      case Some(Outcome.Failed(e: AgentTurnFailed)) => throw e
      case Some(Outcome.Failed(e)) =>
        throw new AgentTurnFailed(
          Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString)
        )
      case None =>
        throw new OrcaFlowException(
          s"$backendName interactive session ended without producing a result"
        )

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      source.interrupt()
      // The reader loop sees EOF on `source.lines` and finalises the
      // outcome on its own — no need to touch the queue from here.

  /** Settle the turn with `result`, then interrupt the source so the reader
    * loop reaches EOF. For drivers whose stream stays open after the turn
    * (SSE), this is how the turn terminates. Setting the outcome **before** the
    * interrupt is load-bearing: closing the source makes the blocked read in
    * [[readLoop]] throw, which would otherwise CAS a failure outcome — harmless
    * only because the success is already in place.
    */
  protected def succeedWith(result: AgentResult[B]): Unit =
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))
    source.interrupt()

  /** Settle the turn as a failure, then interrupt the source (see
    * [[succeedWith]] for the ordering rationale).
    */
  protected def failWith(error: Throwable): Unit =
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.failed(error)))
    source.interrupt()

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

  /** Hook called inside `finalizeLoop` **before** the failure outcome is
    * computed, so subclasses can drain any background streams whose buffered
    * state [[diagnosticContext]] / [[cleanExitWithoutResult]] depend on (codex
    * joins its stderr-drain thread here so the buffered lines reach the
    * exception message). Also where backends release session-scoped resources
    * (claude closes its MCP bridge + Netty server). Default: no-op.
    */
  protected def onFinalize(): Unit = ()

  /** The exception used when the subprocess exits with code 0 without having
    * sent a terminal protocol message. Default: a generic `OrcaFlowException`
    * whose message [[appendContext]]-folds [[diagnosticContext]]. Backends may
    * override outright if they prefer a different framing.
    */
  protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      appendContext(
        s"$backendName exited cleanly but never sent a terminal message"
      )
    )

  /** Optional context the base layer folds into the non-zero-exit /
    * clean-exit-without-result failure messages, so noop-listener callers
    * (programmatic invocations, tests) still get something useful in the thrown
    * exception even when no live listener observed the in-stream
    * `ConversationEvent.Error` events. Default `None`; backends override to
    * attach buffered stderr or similar. The base layer owns the formatting —
    * overrides return just the payload, never the separator.
    */
  protected def diagnosticContext: Option[String] = None

  /** Fold the [[diagnosticContext]] payload (if any) onto a failure-message
    * base. Centralised so every consumer gets the same framing — newline +
    * two-space-indented payload — and overrides don't have to remember to
    * include a leading newline. Subclasses that override
    * [[cleanExitWithoutResult]] to set their own message body should call this
    * so the diagnostic context still flows through.
    */
  protected def appendContext(base: String): String =
    diagnosticContext.fold(base)(ctx => s"$base\n  $ctx")

  // --- Internals ---

  private def readLoop(): Unit =
    try
      for line <- source.lines do
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
      for line <- source.errorLines do
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
    // 1. Subclass hook — typically drains background streams whose buffered
    //    state `diagnosticContext` / `cleanExitWithoutResult` depend on
    //    (codex joins its stderr-drain thread here).
    onFinalize()
    // 2. Close the ask_user resource bundle if one was wired. Happens after
    //    `onFinalize` so any subclass cleanup that might depend on the
    //    bridge / MCP server runs first; in practice neither backend does.
    //    `AskUserSession.close` handles ordering (bridge → server →
    //    extras) and swallows close-time failures.
    askUser.foreach(_.close())
    val finalOutcome: Outcome[B] = outcomeRef.get() match
      case Some(existing)          => existing
      case None if cancelled.get() => Outcome.cancelled[B]
      case None                    => outcomeFromExit(source.tryExitCode)
    val _ = outcomeRef.compareAndSet(None, Some(finalOutcome))
    eventQueue.close()

  private def outcomeFromExit(exitCode: Option[Int]): Outcome[B] =
    exitCode match
      case Some(0) => Outcome.failed[B](cleanExitWithoutResult())
      case Some(code) =>
        Outcome.failed[B](
          new OrcaFlowException(
            appendContext(s"$backendName exited with code $code")
          )
        )
      case None => Outcome.cancelled[B]

private[orca] object StreamConversation:

  /** Internal outcome of the session as the reader sees it. Modelled as a
    * sealed trait + cases (rather than an `enum`) because `Cancelled` and
    * `Failed` are backend-agnostic — using `Nothing` as their `B` makes them
    * assignable to any `Outcome[B]` while the pattern match still narrows
    * `Success`'s `B` correctly.
    *
    * `Outcome` is invariant in `B` because `AgentResult[B]` is invariant (the
    * phantom `B` in `SessionId[B]` etc. is meant to be exact); `Cancelled` and
    * `Failed` get a wide-bounded `Outcome[B]` via the `Outcome.cancelled` /
    * `Outcome.failed` smart constructors below.
    */
  sealed trait Outcome[B <: BackendTag]
  object Outcome:
    final case class Success[B <: BackendTag](result: AgentResult[B])
        extends Outcome[B]
    final case class Cancelled[B <: BackendTag]() extends Outcome[B]
    final case class Failed[B <: BackendTag](error: Throwable)
        extends Outcome[B]

    def cancelled[B <: BackendTag]: Outcome[B] = Cancelled[B]()
    def failed[B <: BackendTag](error: Throwable): Outcome[B] = Failed[B](error)

  /** Default cap on in-flight unread `ConversationEvent`s. Producers block on
    * `put` once full so backpressure flows back into the subprocess pipe
    * (Claude pauses, OS pipe buffer fills, claude blocks). Picked empirically:
    * large enough that a chatty turn doesn't stall, small enough that a slow
    * consumer (user on a long readline) doesn't accumulate unbounded memory.
    */
  val DefaultEventQueueCapacity: Int = 1024

  /** Blocking queue + single-consumer iterator. `close()` signals end-of-stream
    * to whichever thread is iterating.
    *
    * Bounded by `capacity` (default [[DefaultEventQueueCapacity]]) so a slow
    * consumer applies backpressure to the producer — `enqueue` uses `put`,
    * which blocks once the queue is full.
    */
  final class EventQueue(capacity: Int = DefaultEventQueueCapacity):
    private val queue =
      new LinkedBlockingQueue[Option[ConversationEvent]](capacity)

    def enqueue(event: ConversationEvent): Unit = queue.put(Some(event))

    def close(): Unit = queue.put(None)

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
