package orca.runner.terminal

import ox.{Ox, forever, forkDiscard, sleep}
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.io.PrintStream
import java.util.concurrent.Semaphore
import scala.collection.immutable.Queue
import scala.concurrent.duration.DurationLong
import scala.util.control.NonFatal

/** The terminal rendering surface. A small set of operations for appending to
  * the event log and advancing the persistent status row at the bottom of the
  * terminal.
  *
  * Production builds ([[TerminalOutput.start]]) serialise every method on an
  * internal Ox actor: a single worker thread owns `out`, log lines and spinner
  * ticks can't interleave mid-write, and a `forkDiscard` animator inside the
  * factory `tell`s the actor every `framePeriodMs` so the spinner keeps
  * advancing while callers do other work.
  *
  * Tests can instantiate [[TerminalOutputState]] directly — it implements the
  * same interface synchronously without an actor or animator fork.
  *
  * **Prompt transaction.** [[prompt]] is the only way to read from the
  * terminal: it clears the status row, starts buffering concurrent `log` calls,
  * runs the given `readUser` thunk, then drains the buffer and redraws the
  * status row — all as one bracketed unit. A fair semaphore serialises
  * transactions, so two concurrent interactive prompts (a legal fork
  * composition) queue up instead of interleaving; the second's `readUser` only
  * starts once the first's transaction has fully closed. That also serialises
  * access to the shared `readLine` reader, since `readUser` is exactly where
  * callers do that read.
  */
private[terminal] trait TerminalOutput:
  /** Append a (possibly multi-line) chunk to the event log. Trailing newline is
    * normalised. Empty input emits a single newline separator.
    */
  def log(text: String): Unit

  /** Show / relabel / hide the status row. `None` hides it. */
  def setStatus(label: Option[String]): Unit

  /** Run `readUser` as the only writer of the terminal: clears the status row
    * and buffers concurrent `log`/`setStatus` calls for the duration, then
    * drains and redraws on the way out — even if `readUser` throws. A fair
    * semaphore serialises concurrent callers, so a second `prompt` blocks until
    * the first's transaction (including its drain/redraw) has closed; this also
    * protects a shared `readLine` reader from concurrent use.
    */
  def prompt[A](readUser: () => A): A

  /** Flush pending writes, clear the status row, and release the renderer.
    * Tells arriving after close (listener events between this call's return and
    * scope-end) are still processed against the now-cleared state — `log`
    * writes inline without a status row, `tick` is a no-op.
    */
  def close(): Unit

private[terminal] object TerminalOutput:

  /** Build a production `TerminalOutput` whose state is owned by an Ox actor +
    * animator fork in the given scope. The animator is `forkDiscard`: scope-end
    * interrupts it. The IE from `ox.sleep` propagates out of `forever` and is
    * absorbed by the supervisor (scope is already winding down).
    */
  def start(
      out: PrintStream,
      useColor: Boolean,
      animated: Boolean,
      framePeriodMs: Long = 100L
  )(using Ox, BufferCapacity): TerminalOutput =
    val state = new TerminalOutputState(out, useColor, animated)
    val actor = Actor.create(state)
    if animated then
      forkDiscard:
        forever:
          sleep(framePeriodMs.millis)
          actor.tell(_.tick())
    new ActorTerminalOutput(actor)

/** Actor-backed [[TerminalOutput]]. `log`/`setStatus` are tells
  * (fire-and-forget); `suspend`/`resume` (private, only reachable through
  * [[prompt]]) and `close` are asks where the caller needs the operation to
  * have completed before returning. Close-time throws are swallowed so they
  * don't mask whatever already failed upstream.
  *
  * `promptGate` serialises the whole suspend/readUser/resume transaction across
  * concurrent callers: it's acquired before the suspend-ask and released only
  * after the resume-ask, so a second `prompt` call blocks until the first's
  * transaction — drain and redraw included — has fully closed. Fair so waiting
  * forks are served in arrival order rather than risking starvation.
  */
private class ActorTerminalOutput(actor: ActorRef[TerminalOutputState])
    extends TerminalOutput:
  private val promptGate = new Semaphore(1, true)

  def log(text: String): Unit = actor.tell(_.log(text))
  def setStatus(label: Option[String]): Unit =
    actor.tell(_.setStatus(label))
  def prompt[A](readUser: () => A): A =
    promptGate.acquire()
    try
      actor.ask(_.suspend())
      try readUser()
      finally actor.ask(_.resume())
    finally promptGate.release()
  def close(): Unit =
    try actor.ask(_.close())
    catch case NonFatal(_) => ()

/** Mutable rendering state. Not thread-safe in isolation; production wraps it
  * via [[ActorTerminalOutput]]. Tests construct this directly and drive
  * rendering synchronously.
  */
private[terminal] class TerminalOutputState(
    out: PrintStream,
    useColor: Boolean,
    animated: Boolean
) extends TerminalOutput:

  import TerminalOutputState.{
    ClearLine,
    DefaultLabel,
    Frames,
    MaxStatusLineWidth,
    paint
  }

  private var currentLabel: Option[String] = None
  private var frameIndex: Int = 0
  // When `suspended`, the status row is cleared and `log` calls accumulate
  // into `suspendedBuffer` instead of writing to `out`. The animator
  // short-circuits, so no ticks land while a prompt is being read.
  //
  // Cap on the buffer guards against unbounded growth if a flow stays
  // suspended for a long time (e.g. an unattended prompt) while concurrent
  // listeners keep emitting. Past the cap we drop the oldest entry — the
  // newest is more likely to be informative on resume than a stale early
  // line. Cap is large enough that typical interactive prompts don't trim.
  private var suspended: Boolean = false
  private var suspendedBuffer: Queue[String] = Queue.empty
  // Set once `close()` has cleared the status row. Late events (from a fork
  // still unwinding after the scope started closing) may still append log
  // lines, but must not re-pin a status row nothing will clear again.
  private var closed: Boolean = false

  def log(text: String): Unit =
    if suspended then
      suspendedBuffer = suspendedBuffer.enqueue(text)
      if suspendedBuffer.size > TerminalOutputState.SuspendedBufferCap then
        // Queue.dequeue returns (head, rest) — discard the dropped entry.
        suspendedBuffer = suspendedBuffer.tail
    else writeLog(text)

  def setStatus(label: Option[String]): Unit =
    if animated then
      val effective = label.map(s => if s.isEmpty then DefaultLabel else s)
      effective match
        case None =>
          val wasShown = currentLabel.isDefined
          currentLabel = None
          if wasShown then
            out.print(ClearLine)
            out.flush()
        case Some(_) if closed => () // don't re-pin a status row after close
        case some @ Some(_) if suspended =>
          // A prompt owns the terminal; store the label so resume's redraw
          // picks it up, but don't touch `out` — drawing here would repaint
          // over live readLine input.
          currentLabel = some
        case some @ Some(_) =>
          currentLabel = some
          drawStatus()
          out.flush()

  /** Advance the spinner frame. Called by the animator fork; no-op when the bar
    * is hidden or suspended so idle periods don't touch the terminal.
    */
  def tick(): Unit =
    if animated && !suspended && currentLabel.isDefined then
      frameIndex = (frameIndex + 1) % Frames.size
      drawStatus()
      out.flush()

  /** Synchronous implementation of the [[TerminalOutput.prompt]] transaction:
    * this class has no actor and no concurrent callers of its own (production
    * concurrency is serialised one level up, by [[ActorTerminalOutput]]'s
    * semaphore — see its scaladoc), so a plain `suspend`/`finally resume`
    * bracket is enough here.
    */
  def prompt[A](readUser: () => A): A =
    suspend()
    try readUser()
    finally resume()

  /** Clear the status row and start buffering `log` calls; kept as its own
    * method (rather than folded into [[prompt]]) so tests can drive
    * suspend/resume/tick interaction directly without a `readUser` thunk. Not
    * part of the [[TerminalOutput]] trait — [[prompt]] is the only public way
    * to open this window in production.
    *
    * CAUTION: calling `suspend`/`resume` directly (rather than through
    * [[prompt]]) bypasses the `promptGate` semaphore transaction that
    * serializes concurrent prompts (see [[ActorTerminalOutput]]) — production
    * code MUST go through `prompt`; this pair is exposed at package level for
    * tests only.
    */
  def suspend(): Unit =
    if !suspended then
      suspended = true
      // Clear the status row so the prompt can land cleanly. We keep
      // `currentLabel` so resume can redraw the same status without callers
      // having to remember it.
      if animated && currentLabel.isDefined then
        out.print(ClearLine)
        out.flush()

  /** Drain the buffered log lines and redraw the status row. Pairs with
    * [[suspend]]; see its scaladoc for why both stay as separate methods here.
    */
  def resume(): Unit =
    if suspended then
      val toDrain = suspendedBuffer
      suspendedBuffer = Queue.empty
      suspended = false
      toDrain.foreach(writeLog)
      if animated && currentLabel.isDefined then
        drawStatus()
        out.flush()

  def close(): Unit =
    // Drain any buffered log lines so they don't get dropped on shutdown.
    if suspendedBuffer.nonEmpty then
      val toDrain = suspendedBuffer
      suspendedBuffer = Queue.empty
      suspended = false
      toDrain.foreach(writeLog)
    val wasShown = currentLabel.isDefined
    currentLabel = None
    closed = true
    if wasShown && animated then
      out.print(ClearLine)
      out.flush()

  private def writeLog(text: String): Unit =
    if !animated || currentLabel.isEmpty then
      out.print(text)
      if !text.endsWith("\n") then out.println()
      out.flush()
    else
      out.print(ClearLine)
      out.print(text)
      if !text.endsWith("\n") then out.println()
      drawStatus()
      out.flush()

  private def drawStatus(): Unit =
    currentLabel.foreach: label =>
      if animated then
        val frame = Frames(frameIndex)
        // Truncate to a single physical row so each redraw stays anchored to
        // one line — otherwise a long label wraps and the next clear-line
        // only erases the wrapped tail, leaving a staircase of frames.
        val truncated =
          Text.oneLine(label, MaxStatusLineWidth - frame.length - 2)
        out.print(ClearLine)
        out.print(paint(s"$frame $truncated", useColor))

private[terminal] object TerminalOutputState:

  /** Carriage return + ANSI Erase-In-Line-2 (clear entire line). `\u001b` is
    * the ESC byte — written as a Unicode escape so the source stays
    * grep-friendly.
    */
  private val ClearLine: String = "\r\u001b[2K"

  private val DefaultLabel: String = "Thinking..."

  val Frames: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  /** Maximum characters in the rendered status line before truncation. */
  private val MaxStatusLineWidth: Int = 78

  /** Maximum entries the suspended-log buffer holds. Past this the oldest entry
    * is dropped so an unattended prompt with concurrent listener traffic can't
    * grow memory without bound.
    */
  private[terminal] val SuspendedBufferCap: Int = 256

  private def paint(text: String, useColor: Boolean): String =
    Ansi.paint(useColor, fansi.Color.DarkGray, text)
