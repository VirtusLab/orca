package orca.backend

import orca.agents.{BackendTag, StructuredOutputMode}
import orca.backend.mcp.AskUserBridge
import orca.util.{OrcaDebug, TerminalControl}
import orca.{AgentTurnFailed, OrcaInteractiveCancelled}

import ox.{
  Fork,
  Ox,
  UnsupervisedFork,
  discard,
  fork,
  forkDiscard,
  forkUnsupervised,
  pipe
}
import ox.channels.{Channel, ChannelClosed}

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/** A [[Conversation]] over a line [[StreamSource]], decoded by a
  * [[LineDecoder]].
  *
  * [[start]] forks into the caller's per-turn scope: a reader running the
  * decoder's fold over the source's lines, a stderr drain, and — for
  * [[AskUserChannel.Mcp]] — a drain of the agent's questions. The reader
  * enforces the [[ConversationEvent]] turn grammar and returns the turn's
  * outcome, which `awaitResult` joins. The drains can send only
  * [[NeutralEvent]]s, which leave the turn state alone.
  *
  * Teardown: once the decoder settles, the reader SIGINTs the source and, when
  * the root process has exited, kills the process tree, so a descendant still
  * holding stdout or stderr can't keep the turn open. `cancel` interrupts and
  * kills at once.
  */
private[orca] object StreamConversation:

  /** Cap on in-flight unread events. The reader blocks once full, so a slow
    * consumer's backpressure flows back into the subprocess pipe.
    */
  val EventQueueCapacity: Int = 1024

  /** @param decoder
    *   builds the decoder from the conversation's neutral-event sink, for
    *   events a decoder's reply closures report off the reader thread
    */
  def start[B <: BackendTag, S](source: StreamSource, spec: ConversationSpec)(
      decoder: (NeutralEvent => Unit) => LineDecoder[B, S]
  )(using Ox): Conversation[B] =
    val channel = Channel.buffered[ConversationEvent](EventQueueCapacity)
    val neutral: NeutralEvent => Unit = e => channel.sendOrClosed(e).discard
    val lineDecoder = decoder(neutral)
    spec.openingPrompt.foreach(p => neutral(ConversationEvent.UserMessage(p)))
    val cancelled = new AtomicBoolean(false)
    val stderr = fork(drainStderr(source, lineDecoder, neutral))
    spec.askUser match
      case AskUserChannel.Mcp(session) =>
        forkDiscard(drainQuestions(session.bridge, neutral))
      case AskUserChannel.Unavailable | AskUserChannel.Native => ()
    val reader = forkUnsupervised(
      Reader(source, lineDecoder, spec, channel, cancelled, stderr).run()
    )
    Live(spec, source, channel, cancelled, reader)

  /** Prints a raw stream line to stderr when `ORCA_DEBUG_STREAM=1`. */
  def trace(backendName: String, stream: String, line: String): Unit =
    if OrcaDebug.streamTrace then
      System.err.println(s"[orca-debug $backendName-$stream] $line")

  private type Outcome[B <: BackendTag] =
    Either[OrcaInteractiveCancelled | AgentTurnFailed, AgentResult[B]]

  private final class Live[B <: BackendTag](
      spec: ConversationSpec,
      source: StreamSource,
      channel: Channel[ConversationEvent],
      cancelled: AtomicBoolean,
      reader: UnsupervisedFork[Outcome[B]]
  ) extends Conversation[B]:
    def outputSchema: Option[String] = spec.outputSchema
    override def structuredOutputMode: StructuredOutputMode =
      spec.structuredOutputMode
    def canAskUser: Boolean = spec.askUser.isAvailable

    private val iterator: Iterator[ConversationEvent] =
      Iterator
        .continually(channel.receiveOrClosed())
        .takeWhile(!_.isInstanceOf[ChannelClosed])
        .collect { case e: ConversationEvent => e }

    def events(using Ox): Iterator[ConversationEvent] = iterator

    /** Every failure of a turn that ran surfaces as [[AgentTurnFailed]]: the
      * wire session may already exist, so a retry against the same id would
      * only cascade into "already in use". Pre-spawn failures throw from
      * `AgentBackend.open` as plain [[orca.OrcaFlowException]]s and stay
      * retryable.
      */
    def awaitResult()(using
        Ox
    ): Either[OrcaInteractiveCancelled, AgentResult[B]] =
      reader.join() match
        case Right(result)                     => Right(result)
        case Left(c: OrcaInteractiveCancelled) => Left(c)
        case Left(f: AgentTurnFailed)          => throw f

    // SIGINT, then the forcible backstop straight away: this same path runs in
    // the routine `finally` of every turn, where the process has already
    // exited and both are no-ops.
    def cancel(): Unit =
      if cancelled.compareAndSet(false, true) then
        source.interrupt()
        source.destroyForcibly()

  /** The reader's fold state beside the decoder's own. */
  private final case class Progress[B <: BackendTag, S](
      state: S,
      turnOpen: Boolean,
      settled: Option[Settled[B]]
  )

  private final class Reader[B <: BackendTag, S](
      source: StreamSource,
      decoder: LineDecoder[B, S],
      spec: ConversationSpec,
      channel: Channel[ConversationEvent],
      cancelled: AtomicBoolean,
      stderr: Fork[StderrLog]
  )(using Ox):
    private val name = decoder.backendName

    /** Returns the outcome and never throws, so `join` always yields one. */
    def run(): Outcome[B] =
      var progress = Progress[B, S](decoder.init, turnOpen = false, None)
      try
        val readError: Option[Throwable] =
          try
            for line <- source.lines do
              trace(name, "stdout", line)
              if progress.settled.isEmpty && !cancelled.get() then
                progress = step(progress, line)
            None
          catch
            case NonFatal(e) =>
              trace(name, "stdout-error", e.toString)
              Some(e)
        outcome(progress, readError, stderr.join())
      catch
        case NonFatal(t) =>
          trace(name, "reader-error", t.toString)
          Left(
            new AgentTurnFailed(
              describe(t),
              decoder.failedTurnDebit(progress.state),
              t
            )
          )
      finally channel.doneOrClosed().discard

    private def step(progress: Progress[B, S], line: String): Progress[B, S] =
      val decoded =
        try Right(decoder.line(progress.state, line))
        catch case e: Exception => Left(e)
      decoded match
        case Left(e) =>
          send(
            ConversationEvent.Error(
              s"Failed to parse $name line: ${e.getMessage}"
            )
          )
          progress
        case Right(Step.Continue(state, events)) =>
          Progress(state, emitAll(progress.turnOpen, events), None)
        case Right(Step.Settle(state, events, settled)) =>
          // A settle completes the turn, so it owes the closing turn end.
          if emitAll(progress.turnOpen, events) then
            send(ConversationEvent.AssistantTurnEnd)
          stopSource()
          Progress(state, turnOpen = false, Some(settled))

    /** Sends `events` under the turn grammar and returns whether a turn is open
      * afterwards: activity opens a turn, and an `AssistantTurnEnd` closes it —
      * or is dropped when no turn is open, as there are no empty turns.
      */
    private def emitAll(
        turnOpen: Boolean,
        events: List[ConversationEvent]
    ): Boolean =
      events.foldLeft(turnOpen): (open, event) =>
        event match
          case ConversationEvent.AssistantTurnEnd =>
            if open then send(event)
            false
          case _ =>
            send(event)
            open || event.opensTurn

    private def send(event: ConversationEvent): Unit =
      channel.sendOrClosed(event).discard

    /** SIGINT ends the turn's process, or closes a connection; the tree kill
      * waits for the root to exit, so the agent's own shutdown (its session
      * files) completes first.
      */
    private def stopSource(): Unit =
      source.interrupt()
      forkDiscard:
        source.awaitStopped()
        source.destroyForcibly()

    private def outcome(
        progress: Progress[B, S],
        readError: Option[Throwable],
        stderrLog: StderrLog
    ): Outcome[B] =
      def withContext(message: String): String =
        List(decoder.protocolContext(progress.state), stderrLog.context).flatten
          .pipe(ctx =>
            if ctx.isEmpty then message
            else ctx.mkString(s"$message\n  ", "\n    ", "")
          )
      progress.settled match
        case Some(Settled.Succeeded(result)) => Right(result)
        case Some(Settled.Failed(message, debit)) =>
          Left(new AgentTurnFailed(withContext(message), debit))
        case None =>
          spec.onUnsettledEnd()
          val debit = decoder.failedTurnDebit(progress.state)
          // A cancel's kill can make the in-flight read throw rather than EOF,
          // so `cancelled` is checked first: a Ctrl-C is never a failure.
          if cancelled.get() then Left(new OrcaInteractiveCancelled(debit))
          else
            readError match
              case Some(e) => Left(new AgentTurnFailed(describe(e), debit, e))
              case None =>
                Left(
                  new AgentTurnFailed(
                    withContext(unsettledExit(source.tryExitCode)),
                    debit
                  )
                )

    /** A stream that ended with no settle. `None` is a stream that ended with
      * the process still running.
      */
    private def unsettledExit(exitCode: Option[Int]): String =
      exitCode match
        case Some(0) =>
          s"$name exited cleanly but never sent ${decoder.terminalMessageNoun}"
        case Some(code) => s"$name exited with code $code"
        case None =>
          s"$name's output ended without ${decoder.terminalMessageNoun}, " +
            "while the process was still running"

  private def describe(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)

  /** Surfaces each stderr line worth reporting as an `Error` and returns what
    * it surfaced, for the failure messages. Ends at stderr EOF.
    */
  private def drainStderr(
      source: StreamSource,
      decoder: LineDecoder[?, ?],
      neutral: NeutralEvent => Unit
  ): StderrLog =
    var log = StderrLog.empty
    try
      for raw <- source.errorLines do
        trace(decoder.backendName, "stderr", raw)
        val line = TerminalControl.stripControlSequences(raw).trim
        if log.surfaces(line, decoder.isStderrNoise) then
          neutral(ConversationEvent.Error(s"${decoder.backendName}: $line"))
          log = log.add(line)
    catch
      case NonFatal(t) =>
        // The reader doesn't depend on stderr; keep what was collected.
        trace(
          decoder.backendName,
          "stderr-error",
          s"${t.getClass.getName}: ${t.getMessage}"
        )
    log

  /** Each question the agent asks through `ask_user` becomes a `UserQuestion`
    * whose `respond` hands the answer back to the blocked MCP handler. Runs
    * until the turn scope ends.
    */
  private def drainQuestions(
      bridge: AskUserBridge,
      neutral: NeutralEvent => Unit
  ): Unit =
    while true do
      val q = bridge.nextQuestion()
      neutral(ConversationEvent.UserQuestion(q.question, q.respond))
