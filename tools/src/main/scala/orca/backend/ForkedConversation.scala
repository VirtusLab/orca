package orca.backend

import orca.backend.mcp.{AskUserBridge, AskUserSession}
import orca.agents.BackendTag
import orca.util.OrcaDebug
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}

import ox.{Ox, discard, fork, forkUnsupervised, Fork, UnsupervisedFork}
import ox.channels.{Channel, ChannelClosed}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Structured-concurrency base for stream-driven [[Conversation]] drivers — the
  * forked successor to `StreamConversation`. Workers are Ox forks bound to the
  * caller's per-turn scope (hence the `using Ox`), the session outcome is the
  * reader fork's return value, and teardown is a lexical `cancel()` the caller
  * runs in a `finally` (which subsumes the old `finalizeLoop` resource close).
  *
  * Same hook surface as `StreamConversation` so subclasses port with minimal
  * edits: [[handleLine]], [[handleStderr]], [[onFinalize]],
  * [[cleanExitWithoutResult]], [[diagnosticContext]], [[appendContext]],
  * [[askUser]], the `eventQueue.enqueue` shim, and [[succeedWith]] /
  * [[failWith]]. The differences from `StreamConversation`:
  *
  *   - no `start()` / `ensureStarted`: the workers are spawned lazily on first
  *     touch of the conversation surface ([[events]] / [[awaitResult]]), never
  *     from this constructor — spawning here would race the subclass's own
  *     field initializers (which run after this super-constructor) and let the
  *     reader fork call [[handleLine]] against half-built subclass state. By
  *     the time a consumer touches the surface, construction has finished. The
  *     forks bind to the `Ox` captured at construction (the per-turn scope the
  *     backend opened), not to whatever scope is active at first touch — in
  *     every call site construction and consumption share that one scope.
  *   - no `outcomeRef` CAS read by `awaitResult`: the reader fork RETURNS the
  *     `Outcome`; `awaitResult` is `readerFork.join()` mapped as before. The
  *     in-stream settle ([[succeedWith]] / [[failWith]], run on the reader
  *     thread inside [[handleLine]]) stashes its result for the reader's
  *     end-of-stream to pick up.
  */
private[orca] abstract class ForkedConversation[B <: BackendTag](
    source: StreamSource,
    /** Used in fork-internal debug traces, parse-error messages, and the
      * default stderr error prefix. Should match the user-facing backend name.
      */
    backendName: String,
    initialPrompt: String = "",
    /** Set by backends that answer `ask_user` through their own protocol
      * (OpenCode, via a native HTTP `question` event). It only affects what
      * [[canAskUser]] reports — the MCP drainer is gated on [[askUser]] alone.
      */
    nativeAskUser: Boolean = false
)(using Ox)
    extends Conversation[B]:

  import ForkedConversation.*

  /** Bounded event channel: a slow consumer applies backpressure to the
    * producer (the reader's `send` blocks once full) so it flows back into the
    * subprocess pipe. Created with the explicit capacity so no `BufferCapacity`
    * needs threading through the backends.
    */
  private val channel: Channel[ConversationEvent] =
    Channel.buffered(EventQueueCapacity)

  /** `enqueue` / `close` facade over the channel for subclass drivers. It
    * centralises the swallow-on-closed decision in one place: both use the
    * `*OrClosed` variants so a late enqueue (e.g. the ask-user drainer racing
    * the reader's close) is a no-op rather than a thrown `ChannelClosed` that
    * would tear the scope.
    */
  protected final class EventQueue:
    def enqueue(event: ConversationEvent): Unit =
      channel.sendOrClosed(event).discard
    def close(): Unit = channel.doneOrClosed().discard

  protected val eventQueue: EventQueue = new EventQueue

  /** In-stream settled outcome, written once by [[succeedWith]] / [[failWith]]
    * (on the reader thread, inside [[handleLine]]) and read by the reader at
    * end-of-stream. Not read by [[awaitResult]] — the outcome flows out as the
    * reader fork's return value.
    */
  private val settledOutcome: AtomicReference[Option[Outcome[B]]] =
    AtomicReference(None)

  private val cancelled: AtomicBoolean = new AtomicBoolean(false)

  /** Guards the one-shot finalize (subclass [[onFinalize]] + [[askUser]]
    * close): whichever of the reader (happy path) or [[cancel]] (teardown)
    * reaches it first runs it; the other is a no-op, so cancel never
    * double-emits.
    */
  private val finalized: AtomicBoolean = new AtomicBoolean(false)

  /** Optional `ask_user` MCP resource bundle for this conversation. Interactive
    * subclasses override (via `override val askUser` on the ctor param) to
    * point the base at the bundle; the base spawns the drainer fork when the
    * workers start and closes the bundle in the finalize. Autonomous calls
    * leave the default `None`.
    */
  protected def askUser: Option[AskUserSession] = None

  /** True iff an `ask_user` MCP bundle is wired (claude/codex) or the backend
    * declared its own ask-user channel via [[nativeAskUser]] (OpenCode).
    */
  final def canAskUser: Boolean = nativeAskUser || askUser.isDefined

  // Surface the opening prompt before any agent output, so the channel has
  // something to anchor the eventual response against instead of sitting silent
  // while the agent warms up. Lands in the buffer now; the consumer reads it
  // first once the reader starts.
  if initialPrompt.nonEmpty then
    eventQueue.enqueue(ConversationEvent.UserMessage(initialPrompt))

  // --- Workers (Ox forks) ---
  //
  // Spawned lazily, never from this constructor (see the class scaladoc). The
  // reader is `forkUnsupervised` so its (by-design impossible) stray exception
  // surfaces via `join` rather than tearing the per-turn scope; it returns an
  // `Outcome[B]` and never throws. The stderr-drain and ask-user forks are
  // ordinary daemon `fork`s the scope cancels at its end.

  private lazy val stderrFork: Fork[Unit] = fork(stderrLoop())

  private lazy val askUserFork: Unit =
    askUser.foreach(r => fork(askUserDrain(r.bridge)).discard)

  private lazy val readerFork: UnsupervisedFork[Outcome[B]] =
    // Force the auxiliary workers first so stderr/ask-user are running before
    // the reader starts consuming stdout.
    stderrFork.discard
    askUserFork
    forkUnsupervised(runReader())

  private def ensureStarted(): Unit = readerFork.discard

  // --- Conversation surface ---

  def events: Iterator[ConversationEvent] =
    ensureStarted()
    channelIterator

  def awaitResult(): Either[OrcaInteractiveCancelled, AgentResult[B]] =
    readerFork.join() match
      case Outcome.Success(r)  => Right(r)
      case Outcome.Cancelled() => Left(new OrcaInteractiveCancelled())
      // A failure here means the turn ran (the session is registered) and then
      // errored — tag it non-retryable so the autonomous retry loop doesn't
      // reopen the locked session id. See [[AgentTurnFailed]].
      case Outcome.Failed(e: AgentTurnFailed) => throw e
      case Outcome.Failed(e) =>
        throw new AgentTurnFailed(
          Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString)
        )

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      // Graceful SIGINT first, then the guaranteed forcible backstop, so the
      // non-interruptible reader's `source.lines` always reaches EOF and the
      // scope join never hangs. On the happy path both are no-ops (the source
      // already ended). Then run the full finalize (subsuming the old
      // `finalizeLoop` resource close); the `finalized` guard means the reader,
      // if it got there first, isn't re-run.
      source.interrupt()
      source.destroyForcibly()
      runFinalize()

  // --- Settle helpers (called from handleLine, on the reader thread) ---

  /** Settle the turn with `result`, then interrupt the source so the reader
    * loop reaches EOF. For drivers whose stream stays open after the turn
    * (SSE), this is how the turn terminates; for subprocess drivers the source
    * closes on its own and the interrupt is a harmless early teardown.
    */
  protected def succeedWith(result: AgentResult[B]): Unit =
    settledOutcome.compareAndSet(None, Some(Outcome.Success(result))).discard
    source.interrupt()

  /** Settle the turn as a failure, then interrupt the source (see
    * [[succeedWith]] for the ordering rationale).
    */
  protected def failWith(error: Throwable): Unit =
    settledOutcome.compareAndSet(None, Some(Outcome.failed(error))).discard
    source.interrupt()

  // --- Hooks for backend implementations ---

  /** Process a single line of stdout. Implementations parse the protocol
    * message and translate to [[ConversationEvent]] enqueues (and/or
    * [[succeedWith]] / [[failWith]] settles). Exceptions thrown here are caught
    * by the reader and surfaced as a generic parse-error Error event.
    */
  protected def handleLine(line: String): Unit

  /** Process one line of stderr. Default: enqueue as an Error event with the
    * backend name as a prefix. Override to filter known-noise lines.
    */
  protected def handleStderr(line: String): Unit =
    if line.trim.nonEmpty then
      eventQueue.enqueue(ConversationEvent.Error(s"$backendName: $line"))

  /** Hook called inside the finalize **before** the failure outcome is
    * computed, so subclasses can drain background streams whose buffered state
    * [[diagnosticContext]] / [[cleanExitWithoutResult]] depend on (join the
    * [[stderrDrainFork]] here). Also where backends release session-scoped
    * resources. Runs exactly once (reader happy-path OR [[cancel]]). Default:
    * no-op.
    */
  protected def onFinalize(): Unit = ()

  /** The exception used when the subprocess exits with code 0 without having
    * sent a terminal protocol message. Default folds [[diagnosticContext]] into
    * the message; backends may override.
    */
  protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      appendContext(
        s"$backendName exited cleanly but never sent a terminal message"
      )
    )

  /** Optional context the base folds into the non-zero-exit / clean-exit
    * failure messages, so noop-listener callers still get something useful in
    * the thrown exception. Default `None`; backends override to attach buffered
    * stderr. Overrides return just the payload, never the separator.
    */
  protected def diagnosticContext: Option[String] = None

  /** Fold the [[diagnosticContext]] payload (if any) onto a failure-message
    * base — newline + two-space-indented payload. Centralised so every consumer
    * gets the same framing.
    */
  protected def appendContext(base: String): String =
    diagnosticContext.fold(base)(ctx => s"$base\n  $ctx")

  /** The stderr-drain fork, for backends whose [[onFinalize]] needs to wait for
    * stderr to flush before computing a diagnostic-bearing failure.
    */
  protected def stderrDrainFork: Fork[Unit] = stderrFork

  protected def debugLog(channel: String, line: String): Unit =
    if OrcaDebug.streamTrace then
      System.err.println(s"[orca-debug $backendName-$channel] $line")

  // --- Internals ---

  /** Sole outcome producer. Catches everything and RETURNS an `Outcome[B]` —
    * never throws (H1), so `readerFork.join()` always yields an outcome.
    */
  private def runReader(): Outcome[B] =
    try
      val readException: Option[Throwable] =
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
          None
        catch
          case NonFatal(e) =>
            debugLog("stdout-error", e.toString)
            Some(e)
      // Finalize (drain background streams, close session resources) BEFORE
      // computing a failure outcome so `diagnosticContext` is populated.
      runFinalize()
      val outcome: Outcome[B] =
        // A user cancel (`destroyForcibly`) can make the in-flight read throw
        // rather than EOF cleanly, so `cancelled` is checked BEFORE
        // `readException` — a Ctrl-C must surface as `Cancelled`, never as a
        // spurious turn failure.
        settledOutcome
          .get()
          .orElse(Option.when(cancelled.get())(Outcome.cancelled[B]))
          .orElse(readException.map(Outcome.failed[B]))
          .getOrElse(outcomeFromExit(source.tryExitCode))
      eventQueue.close()
      outcome
    catch
      case NonFatal(t) =>
        debugLog("reader-error", t.toString)
        eventQueue.close()
        Outcome.failed[B](t)

  private def stderrLoop(): Unit =
    try
      for line <- source.errorLines do
        debugLog("stderr", line)
        handleStderr(line)
    catch
      case NonFatal(t) =>
        // Best-effort — the reader doesn't depend on it. Surface the swallowed
        // throwable under ORCA_DEBUG so a real bug isn't masked.
        debugLog("stderr-error", s"${t.getClass.getName}: ${t.getMessage}")

  /** Bridge an [[AskUserBridge]] into the event stream: each pending question
    * becomes a `UserQuestion` whose `respond` closure delivers the user's typed
    * answer back to the blocked MCP handler. Exits cleanly when
    * `bridge.close()` (driven by the finalize) raises `ChannelClosedException`
    * from `nextQuestion()`.
    */
  private def askUserDrain(bridge: AskUserBridge): Unit =
    try
      while true do
        val q = bridge.nextQuestion()
        eventQueue.enqueue(
          ConversationEvent.UserQuestion(q.question, q.respond)
        )
    catch case NonFatal(_) => ()

  private def runFinalize(): Unit =
    if finalized.compareAndSet(false, true) then
      // 1. Subclass hook — typically joins the stderr-drain fork so trailing
      //    lines reach the queue / `diagnosticContext` before the outcome.
      onFinalize()
      // 2. Close the ask_user bundle (bridge → server → extras) if wired, after
      //    `onFinalize` so any cleanup depending on it runs first. Idempotent.
      askUser.foreach(_.close())

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

  /** Single-consumer iterator over the event channel; `done` ends it. */
  private val channelIterator: Iterator[ConversationEvent] =
    new Iterator[ConversationEvent]:
      // null — nothing peeked yet (will block on next `hasNext`); Some(e) —
      // peeked event; None — stream closed, `hasNext` stays false forever.
      private var peeked: Option[ConversationEvent] = null

      def hasNext: Boolean =
        if peeked == null then
          peeked = channel.receiveOrClosed() match
            case _: ChannelClosed     => None
            case e: ConversationEvent => Some(e)
        peeked.isDefined

      def next(): ConversationEvent =
        if !hasNext then throw new NoSuchElementException("event stream closed")
        val value = peeked.get
        peeked = null
        value

private[orca] object ForkedConversation:

  /** Cap on in-flight unread `ConversationEvent`s (today's `StreamConversation`
    * value). The producer blocks on `send` once full so backpressure flows back
    * into the subprocess pipe.
    */
  val EventQueueCapacity: Int = 1024

  /** Internal outcome of the session as the reader sees it. Modelled as a
    * sealed trait + cases so `Cancelled` / `Failed` are backend-agnostic
    * (`Nothing` for their `B` keeps them assignable to any `Outcome[B]` while
    * the match still narrows `Success`'s `B`). `Outcome` is invariant in `B`
    * because `AgentResult[B]` is.
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
