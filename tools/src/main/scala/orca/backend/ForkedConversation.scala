package orca.backend

import orca.backend.mcp.{AskUserBridge, AskUserSession}
import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.Usage
import orca.util.OrcaDebug
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}

import ox.{Ox, discard, fork, forkUnsupervised, Fork, UnsupervisedFork}
import ox.channels.{Channel, ChannelClosed}

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/** Structured-concurrency base for stream-driven [[Conversation]] drivers.
  * Workers are Ox forks bound to the caller's per-turn scope, the session
  * outcome is the reader fork's return value, and teardown is a lexical
  * `cancel()` the caller runs in a `finally`.
  *
  * '''Startup''': the workers (a stderr drain, an optional ask-user drainer,
  * and the reader) are started once, lazily, by [[ensureStarted]] — invoked
  * from the conversation surface ([[events]] / [[awaitResult]]), never from
  * this constructor. Two reasons the capability lives on the surface methods
  * rather than the constructor: (1) spawning from the constructor would race
  * the subclass's own field initializers (which run after this
  * super-constructor) and let the reader fork call [[handleLine]] against
  * half-built subclass state; by the time a consumer touches the surface,
  * construction has finished. (2) keeping constructors capability-free (the
  * owner's Ox method-scoping rule) — the `using Ox` the workers fork into is
  * the per-turn supervised scope the backend's drain shell already opened
  * (`Conversations.runAutonomous` / `AgentCall.runInteractiveOnce`), which is
  * also the scope construction happens in, so the fork lifetime is unchanged.
  *
  * '''Outcome plumbing''': there's no polled outcome ref — the reader fork
  * RETURNS the `Outcome[B]`, and [[awaitResult]] is just the reader fork's join
  * mapped. An in-stream settle ([[succeedWith]] / [[failWith]], run on the
  * reader thread from inside [[handleLine]]) stashes its result for the
  * reader's end-of-stream to pick up.
  *
  * Subclasses implement a driver by supplying these hooks, grouped by role:
  *   - '''Per-line translation''': [[handleLine]] (abstract — parse one stdout
  *     line into enqueues and/or a settle) and [[handleStderr]] (default:
  *     enqueue as an `Error`; the `StderrPipeline` mix-ins override it `final`
  *     and vary only their `isStderrNoise` predicate).
  *   - '''Settle''': [[succeedWith]] / [[failWith]] end the turn with a result
  *     or a failure.
  *   - '''Turn-grammar reads''': [[turnIsOpen]] / [[isSettled]] let a driver
  *     ask "did activity stream this turn?" / "have we already settled?"
  *     without its own bookkeeping.
  *   - '''Lifecycle''': [[onFinalize]] (drain background streams, release
  *     session resources — runs exactly once), [[onCancelRequested]] (genuine
  *     mid-turn cancel only, never a routine post-success teardown), and
  *     [[cleanExitWithoutResult]] (the exception for a zero-exit stream that
  *     never sent a terminal message).
  *   - '''Diagnostics''': [[diagnosticContext]] / [[appendContext]] fold
  *     optional buffered context into failure messages.
  *   - '''Ask-user''': [[askUser]] wires an MCP `ask_user` bundle; the base
  *     spawns its drainer fork and closes it at finalize.
  *   - '''Enqueue contract''': all events reach the channel through
  *     `eventQueue.enqueue`, which enforces the [[ConversationEvent]] turn
  *     grammar by construction. Callers from a background fork (the stderr
  *     loop, the ask-user drainer) MUST only ever pass NEUTRAL events — see
  *     [[EventQueue]]'s confinement contract.
  */
private[orca] abstract class ForkedConversation[B <: BackendTag](
    source: StreamSource,
    /** Used in fork-internal debug traces, parse-error messages, and the
      * default stderr error prefix. Should match the user-facing backend name.
      * `protected` so [[StderrPipeline]]'s hoisted `handleStderr` can build the
      * same `"$backendName: $line"` prefix each subprocess backend used to
      * hardcode individually.
      */
    protected val backendName: String,
    initialPrompt: String = "",
    /** Set by backends that answer `ask_user` through their own protocol
      * (OpenCode, via a native HTTP `question` event). It only affects what
      * [[canAskUser]] reports — the MCP drainer is gated on [[askUser]] alone.
      */
    nativeAskUser: Boolean = false
) extends Conversation[B]:

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
    *
    * It ALSO enforces the [[ConversationEvent]] turn grammar by construction,
    * so backends no longer hand-roll it: activity events open the current turn,
    * an `AssistantTurnEnd` closes an open turn (and is DROPPED as an empty turn
    * if none is open), and the settle helpers auto-close a still-open turn (see
    * [[succeedWith]] / [[failWith]]). The activity/neutral classification
    * routes through [[ConversationEvent.opensTurn]], the single source of truth
    * for that split — an exhaustive match, so a future `ConversationEvent` case
    * can't silently fall through as neutral here.
    *
    * '''Confinement contract''': `enqueue` mutates the reader-thread-confined
    * [[turnIsOpen]] state, so any caller from a background fork (`stderrLoop`,
    * `askUserDrain`) MUST only ever pass NEUTRAL events — verified today across
    * all five backends (stderr enqueues only `Error`; the ask-user drainer only
    * `UserQuestion`). A future driver that emits an activity event or an
    * `AssistantTurnEnd` off the reader thread would race `turnIsOpen` with no
    * compiler or test signal; see [[turnIsOpen]]'s single-writer note.
    */
  protected final class EventQueue:
    def enqueue(event: ConversationEvent): Unit =
      event match
        // AssistantTurnEnd has its own forward/drop arm, ahead of the
        // activity/neutral split below: it closes an open turn, or is DROPPED
        // as an empty turn if none is open (fixes gemini's unconditional
        // pre-result end and claude's suppressed ask_user-only turn).
        case ConversationEvent.AssistantTurnEnd =>
          if openTurn then
            openTurn = false
            channel.sendOrClosed(event).discard
        // Activity (turn-opening) vs. neutral (UserMessage, Error,
        // ApproveTool, UserQuestion): ConversationEvent.opensTurn is the
        // exhaustive, single-source-of-truth classifier — see its scaladoc.
        case _ if event.opensTurn =>
          openTurn = true
          channel.sendOrClosed(event).discard
        case _ =>
          channel.sendOrClosed(event).discard
    def close(): Unit = channel.doneOrClosed().discard

  protected val eventQueue: EventQueue = new EventQueue

  /** In-stream settled outcome, written once by [[succeedWith]] / [[failWith]]
    * (on the reader thread, inside [[handleLine]]) and read by the reader at
    * end-of-stream (`runReader`, same thread) — that side is still a
    * single-WRITER property: every caller of `succeedWith`/`failWith` across
    * the backends (claude, codex, gemini, pi, opencode) settles from inside its
    * `handleLine` dispatch, so "first write wins" needs no CAS.
    *
    * But [[isSettled]] is also read from [[cancel]], which runs on the DRIVING
    * thread (the caller's `finally`, e.g. `AgentCall`/`Conversations`, or
    * `ConversationRenderer` on a prompt interrupt) — a genuinely cross-thread
    * read racing the reader's write with no channel operation or other
    * synchronization action between them. `@volatile` is what makes that read
    * see a completed settle rather than a stale `None`; without it a cancel
    * racing the reader's last line could observe `isSettled == false` and fire
    * a backend's real abort-the-turn hook ([[onCancelRequested]]) on a turn
    * that just succeeded.
    */
  @volatile private var settledOutcome: Option[Outcome[B]] = None

  /** True once [[succeedWith]]/[[failWith]] has settled the outcome — for
    * subclasses that must drop post-terminal wire frames.
    */
  protected def isSettled: Boolean = settledOutcome.isDefined

  /** Whether a turn is currently open — set by [[EventQueue.enqueue]] when an
    * activity event flows and cleared by the closing `AssistantTurnEnd` (or the
    * settle auto-close). Read by the settle helpers here and exposed to
    * subclasses so a driver can ask "did activity stream this turn?" without
    * its own bookkeeping.
    *
    * '''Reader-thread-confined invariant''' (distinct from [[settledOutcome]]'s
    * — that field has a genuine cross-thread reader in [[cancel]], which is why
    * it's `@volatile`; `openTurn` has no such reader): `openTurn` is written
    * only from `enqueue` on activity/turn-end events and from the settle-time
    * auto-close — all of which run on the reader thread, inside [[handleLine]]
    * or the reader's end-of-stream — and read only via [[turnIsOpen]] from that
    * same thread ([[closeOpenTurn]], and subclasses' reads happen from inside
    * their own `handleLine`). The other two `enqueue` producers run on
    * different threads (`stderrLoop`, `askUserDrain`) but only ever pass
    * NEUTRAL events, which don't touch this field (the confinement contract
    * stated on [[EventQueue]]). So both single-writer AND single-reader are
    * plain-`var` properties, not a coincidence — but an implicit one: a future
    * driver emitting activity off the reader thread, or a cross-thread read of
    * [[turnIsOpen]], would silently break it.
    */
  private var openTurn: Boolean = false

  /** The reader thread's identity, captured once at the top of [[runReader]]
    * (before any [[handleLine]] dispatch can run) so [[succeedWith]] /
    * [[failWith]] can assert the single-writer invariant [[settledOutcome]]'s
    * scaladoc documents — every backend settles from inside its own
    * `handleLine`, on this thread — rather than let a future backend #6 that
    * settles from a background fork silently violate it. Written once,
    * read-after-write on the same thread that wrote it (the reader), so a plain
    * `var` needs no `@volatile`: unlike [[settledOutcome]], nothing reads this
    * cross-thread. `null` only before the reader fork starts, which is also
    * before either settle method can possibly run.
    */
  private var readerThread: Thread | Null = null

  /** Single-writer check for [[succeedWith]] / [[failWith]]: neither may run
    * off the reader thread. A plain (unelided) `assert` rather than an
    * `OrcaDebug`-gated one — this is a backend-authoring contract violation (a
    * driver settling from a background fork instead of `handleLine`), always a
    * bug and never a legitimate runtime condition a production flow should
    * tolerate silently, so it's not worth hiding behind a debug flag a real
    * user run would never think to set. Deliberately does NOT touch
    * [[isSettled]]'s read from [[cancel]], which runs on the driving thread by
    * design.
    */
  private def assertReaderThread(caller: String): Unit =
    assert(
      Thread.currentThread() eq readerThread,
      s"$backendName: $caller invoked off the reader thread " +
        s"(${Thread.currentThread()} != $readerThread) — settledOutcome's " +
        "single-writer invariant requires every settle to happen from " +
        "inside handleLine on the reader thread"
    )

  /** True iff a turn is currently open (activity streamed since the last
    * `AssistantTurnEnd`). The base reads it to decide the settle auto-close;
    * subclasses may read it in place of their own turn-openness flags.
    */
  protected final def turnIsOpen: Boolean = openTurn

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
  // Started once, lazily, from the conversation surface ([[ensureStarted]], via
  // [[events]] / [[awaitResult]]), never from this constructor (see the class
  // scaladoc). The reader is `forkUnsupervised` so its (by-design impossible)
  // stray exception surfaces via `join` rather than tearing the per-turn scope;
  // it returns an `Outcome[B]` and never throws. The stderr-drain and ask-user
  // forks are ordinary daemon `fork`s the scope cancels at its end.

  private case class Workers(reader: UnsupervisedFork[Outcome[B]])

  /** The started workers, written once by [[ensureStarted]]. Read by
    * [[awaitResult]] (reader join) on the consuming thread — `@volatile` so a
    * `cancel` racing the first `events` / `awaitResult` sees the started fork
    * (or a definite `None`), never a torn half-initialised reference.
    */
  @volatile private var workers: Option[Workers] = None

  /** The stderr-drain fork handle, in its own field written BEFORE the reader
    * fork starts: the reader's happy-path finalize joins [[stderrDrainFork]]
    * from the reader's own thread, and on an instant-EOF stream it can get
    * there before `ensureStarted`'s later writes land — reading `None` there
    * would silently skip the drain join and drop the stderr diagnostics from
    * the failure message.
    */
  @volatile private var stderrFork: Option[Fork[Unit]] = None

  /** Start the reader / stderr / ask-user forks on first touch of the surface;
    * a no-op thereafter, returning the already-started set. The auxiliary
    * workers are forked (and [[stderrFork]] published) before the reader, so
    * stderr/ask-user are running — and the drain handle is visible to the
    * reader's finalize — before the reader consumes stdout. The consumer is
    * single-threaded ([[events]] then [[awaitResult]] on the same thread), so
    * the check-then-start needs no lock.
    */
  private def ensureStarted()(using Ox): Workers =
    workers.getOrElse:
      stderrFork = Some(fork(stderrLoop()))
      askUser.foreach(r => fork(askUserDrain(r.bridge)).discard)
      val started = Workers(forkUnsupervised(runReader()))
      workers = Some(started)
      started

  // --- Conversation surface ---

  def events(using Ox): Iterator[ConversationEvent] =
    ensureStarted().discard
    channelIterator

  /** THE retryability classifier: every post-spawn failure surfacing here is
    * (re)thrown as [[AgentTurnFailed]] — by the time the reader fork settles,
    * the turn has run and the wire session may already exist, so a retry
    * against the same id would only cascade into "already in use" / a broken
    * pipe rather than a clean re-attempt. Pre-spawn *open* failures never reach
    * this method at all — they throw from `openConversation` (before a
    * `Conversation` exists to call `awaitResult` on) as plain
    * [[OrcaFlowException]]s and stay retryable. The retry POLICY that acts on
    * this classification lives in `DefaultAgentCall.runAutonomousWithRetry`.
    */
  def awaitResult()(using
      Ox
  ): Either[OrcaInteractiveCancelled, AgentResult[B]] =
    ensureStarted().reader.join() match
      case Outcome.Success(r)  => Right(r)
      case Outcome.Cancelled() => Left(new OrcaInteractiveCancelled())
      case Outcome.Failed(e: AgentTurnFailed) => throw e
      case Outcome.Failed(e) =>
        throw new AgentTurnFailed(
          Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString),
          e
        )

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      // Only a turn that hasn't settled yet is a GENUINE cancel (the user hit
      // Ctrl-C, or the driving loop unwound mid-turn); a turn already settled
      // via succeedWith/failWith is merely being torn down here in a
      // `finally` after it finished on its own, so the hook must not see it
      // as a cancellation. Checked before the interrupt/destroy sequence,
      // which must always run regardless (idle teardown is harmless there).
      if !isSettled then onCancelRequested()
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
    assertReaderThread("succeedWith")
    if settledOutcome.isEmpty then
      closeOpenTurn()
      settledOutcome = Some(Outcome.Success(result))
    source.interrupt()

  /** Assemble an [[AgentResult]] from a driver's synthesised turn values and
    * settle with it via [[succeedWith]]. Wraps the reported wire-id string in
    * [[WireSessionId]]`[B]` and the optional model-id string in [[Model]] —
    * collapsing the byte-identical settle scaffolding every driver used to
    * spell out (a `WireSessionId[…]` + `Model.apply` +
    * `succeedWith(AgentResult(…))`).
    */
  protected def settleSuccess(
      wireId: String,
      output: String,
      usage: Usage,
      modelId: Option[String]
  ): Unit =
    succeedWith(
      AgentResult(
        WireSessionId[B](wireId),
        output,
        usage,
        modelId.map(Model.apply)
      )
    )

  /** Settle the turn as a failure, then interrupt the source (see
    * [[succeedWith]] for the ordering rationale).
    */
  protected def failWith(error: Throwable): Unit =
    assertReaderThread("failWith")
    if settledOutcome.isEmpty then
      closeOpenTurn()
      settledOutcome = Some(Outcome.failed(error))
    source.interrupt()

  /** Terminate an open turn at settle time by enqueuing the owed
    * `AssistantTurnEnd` (which closes it via the funnel). A no-op when no turn
    * is open, so a driver that already emitted its own protocol-driven turn end
    * before settling never double-emits — its end closed the turn, this sees
    * none open. Runs on the reader thread, before `settledOutcome` is set, so
    * the closing event reaches the channel ahead of the reader's close.
    */
  private def closeOpenTurn(): Unit =
    if turnIsOpen then eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)

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

  /** Hook called from inside [[cancel]]'s idempotence guard, but ONLY when the
    * turn hasn't already settled (see [[isSettled]]) — i.e. exactly the genuine
    * "torn down mid-turn" case, never the routine `finally cancel()` that every
    * caller runs after a turn that already succeeded or failed on its own. Runs
    * before the interrupt/destroy sequence. Default: no-op. Backends that need
    * to notify a remote peer the turn is being abandoned (e.g. OpenCode's `POST
    * /abort`) override this instead of [[cancel]], so that notification never
    * fires for a session merely being torn down after a normal turn.
    */
  protected def onCancelRequested(): Unit = ()

  /** The protocol message whose absence a clean (exit-0) stream is diagnosed
    * against, named for the message — `"a result message"`, `"a turn.completed
    * event"`, … Backends override just this noun; the framing (backend name +
    * folded [[diagnosticContext]]) is shared in [[cleanExitWithoutResult]].
    */
  protected def terminalMessageNoun: String = "a terminal message"

  /** The exception used when the subprocess exits with code 0 without having
    * sent its [[terminalMessageNoun]]. Folds [[diagnosticContext]] into the
    * message (a no-op for backends that carry none); backends vary only the
    * noun, not this assembly.
    */
  protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      appendContext(
        s"$backendName exited cleanly but never sent $terminalMessageNoun"
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
    * stderr to flush before computing a diagnostic-bearing failure. `None` when
    * the workers were never started — a conversation constructed but never
    * consumed, then cancelled — so there is nothing to join.
    */
  protected def stderrDrainFork: Option[Fork[Unit]] = stderrFork

  protected def debugLog(channel: String, line: String): Unit =
    if OrcaDebug.streamTrace then
      System.err.println(s"[orca-debug $backendName-$channel] $line")

  // --- Internals ---

  /** Sole outcome producer. Catches everything and RETURNS an `Outcome[B]` —
    * never throws (H1), so the reader fork's `join()` always yields an outcome.
    */
  private def runReader(): Outcome[B] =
    readerThread = Thread.currentThread()
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
      private enum Peek:
        case Empty // nothing buffered; next `hasNext` blocks on the channel
        case Ready(event: ConversationEvent) // buffered, not yet consumed
        case Closed // stream ended; `hasNext` stays false forever

      // Consumer-thread-confined: only the single thread draining `events` ever
      // touches `peek` (the reader fork is the producer and never reads it), so
      // a plain `var` (no atomics) is correct.
      private var peek: Peek = Peek.Empty

      def hasNext: Boolean =
        if peek == Peek.Empty then
          peek = channel.receiveOrClosed() match
            case _: ChannelClosed     => Peek.Closed
            case e: ConversationEvent => Peek.Ready(e)
        peek match
          case Peek.Ready(_)            => true
          case Peek.Closed | Peek.Empty => false

      def next(): ConversationEvent =
        if !hasNext then throw new NoSuchElementException("event stream closed")
        val Peek.Ready(value) = peek: @unchecked
        peek = Peek.Empty
        value

private[orca] object ForkedConversation:

  /** Cap on in-flight unread `ConversationEvent`s. The producer blocks on
    * `send` once full so backpressure flows back into the subprocess pipe.
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
