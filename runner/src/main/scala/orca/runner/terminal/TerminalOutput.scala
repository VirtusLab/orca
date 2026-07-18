package orca.runner.terminal

import ox.{Ox, forever, forkDiscard, sleep}
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.io.PrintStream
import java.util.concurrent.Semaphore
import scala.collection.immutable.Queue
import scala.concurrent.duration.DurationLong
import scala.util.control.NonFatal

/** The terminal rendering surface: appends to the event log and advances the
  * persistent status row pinned at the bottom.
  *
  * Production builds ([[TerminalOutput.start]]) serialise every method on an
  * internal Ox actor, so a single worker thread owns `out` and log lines can't
  * interleave with spinner ticks. Tests can instantiate [[TerminalOutputState]]
  * directly — the same interface, synchronous, no actor or animator fork.
  *
  * **Prompt transaction.** [[prompt]] is the only way to read from the
  * terminal: it clears the status row, buffers concurrent `log` calls, runs
  * `readUser`, then drains and redraws — all as one bracketed unit. A fair
  * semaphore serialises transactions so two concurrent prompts queue instead of
  * interleaving, which also serialises the shared `readLine` reader.
  */
private[terminal] trait TerminalOutput:
  /** Append a (possibly multi-line) chunk to the event log. Trailing newline is
    * normalised. Empty input emits a single newline separator.
    */
  def log(text: String): Unit

  /** Show / relabel / hide the status row. `None` hides it. */
  def setStatus(label: Option[String]): Unit

  /** Run `readUser` as the only writer of the terminal — see the class
    * scaladoc's "Prompt transaction". Drains and redraws even if `readUser`
    * throws.
    */
  def prompt[A](readUser: () => A): A

  /** Flush pending writes, clear the status row, and release the renderer.
    * Tells arriving after close are still processed against the cleared state:
    * `log` writes inline without a status row, `tick` is a no-op.
    */
  def close(): Unit

private[terminal] object TerminalOutput:

  /** Build a production `TerminalOutput` whose state is owned by an Ox actor +
    * animator fork in the given scope. The animator is `forkDiscard`, so
    * scope-end interrupts it; the IE from `ox.sleep` is absorbed by the
    * supervisor as the scope winds down.
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

/** Actor-backed [[TerminalOutput]]. `log`/`setStatus` are tells; `suspend`/
  * `resume` and `close` are asks (the caller needs completion before
  * returning). Close-time throws are swallowed so they don't mask an upstream
  * failure.
  *
  * `promptGate` (fair) is held from before the suspend-ask until after the
  * resume-ask, so a second `prompt` blocks until the first transaction — drain
  * and redraw included — has fully closed.
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
  // When `suspended`, the status row is cleared and `log` calls accumulate into
  // `suspendedBuffer` instead of writing to `out`; the animator short-circuits.
  // Past SuspendedBufferCap the oldest entry is dropped (the newest is more
  // likely informative on resume) so an unattended prompt can't grow unbounded.
  private var suspended: Boolean = false
  private var suspendedBuffer: Queue[String] = Queue.empty
  // Set once `close()` has cleared the status row. Late events (a fork still
  // unwinding after scope-close) may append log lines but must not re-pin a
  // status row nothing will clear again.
  private var closed: Boolean = false

  def log(text: String): Unit =
    if suspended then
      suspendedBuffer = suspendedBuffer.enqueue(text)
      if suspendedBuffer.size > TerminalOutputState.SuspendedBufferCap then
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
          // A prompt owns the terminal: store the label for resume's redraw but
          // don't touch `out`, which would repaint over live readLine input.
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

  /** Synchronous [[TerminalOutput.prompt]]: no actor or concurrent callers here
    * (production serialises one level up in [[ActorTerminalOutput]]), so a
    * plain `suspend`/`finally resume` bracket suffices.
    */
  def prompt[A](readUser: () => A): A =
    suspend()
    try readUser()
    finally resume()

  /** Clear the status row and start buffering `log` calls. A separate method
    * (not folded into [[prompt]]) so tests can drive suspend/resume/tick
    * directly.
    *
    * CAUTION: calling `suspend`/`resume` directly bypasses the `promptGate`
    * transaction that serialises concurrent prompts (see
    * [[ActorTerminalOutput]]) — production code MUST go through [[prompt]].
    * Exposed at package level for tests only.
    */
  def suspend(): Unit =
    if !suspended then
      suspended = true
      // Keep `currentLabel` so resume can redraw the same status.
      if animated && currentLabel.isDefined then
        out.print(ClearLine)
        out.flush()

  /** Drain the buffered log lines and redraw the status row. Pairs with
    * [[suspend]].
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
    * the ESC byte, a Unicode escape so the source stays grep-friendly.
    */
  private val ClearLine: String = "\r\u001b[2K"

  private val DefaultLabel: String = "Thinking..."

  val Frames: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  /** Maximum characters in the rendered status line before truncation. */
  private val MaxStatusLineWidth: Int = 78

  /** Maximum entries the suspended-log buffer holds before the oldest is
    * dropped.
    */
  private[terminal] val SuspendedBufferCap: Int = 256

  private def paint(text: String, useColor: Boolean): String =
    Ansi.paint(useColor, fansi.Color.DarkGray, text)
