package orca.runner.terminal

import java.io.PrintStream

/** A persistent single-line status indicator at the bottom of the terminal,
  * with the event log accumulating above. The status line shows the current
  * activity (stage name + spinner glyph); each log write transparently scrolls
  * it down by one row.
  *
  * Design:
  *   - All event-log output flows through [[appendLog]]. The bar clears its
  *     current status line, writes the log line, then re-draws the status below
  *     it.
  *   - [[tick]] advances the spinner glyph in place without touching the log.
  *     [[TerminalInteraction.start]] owns a daemon fork that periodically tells
  *     the enclosing actor to call this method, so spinner ticks land on the
  *     same mailbox as log writes and stage transitions.
  *   - When [[animated]] is false (non-TTY output, redirected stderr, CI), the
  *     bar degrades to plain inline output: every appendLog just writes the
  *     line, and the spinner becomes a no-op.
  *
  * **Not thread-safe.** Callers must serialise access — production code does so
  * via the TerminalInteraction actor's single worker thread; tests construct
  * the bar directly and drive it synchronously.
  */
private[terminal] class StatusBar(
    out: PrintStream,
    useColor: Boolean,
    animated: Boolean = true
):

  import StatusBar.{ClearLine, DefaultLabel, Frames, MaxStatusLineWidth, paint}

  // `null` means "no status set"; non-null means "status visible at the
  // current cursor row, redraw on appendLog / tick".
  private var currentLabel: String | Null = null
  private var frameIndex: Int = 0

  /** Append a chunk of text to the event log above the status line. The chunk
    * may contain `\n`s; trailing newline is normalised so each appended chunk
    * ends one logical row. Empty input emits just the trailing newline (used by
    * callers as a section separator).
    */
  def appendLog(text: String): Unit =
    if !animated || currentLabel == null then
      out.print(text)
      if !text.endsWith("\n") then out.println()
      out.flush()
    else
      // 1. Clear the status row so the log line lands cleanly.
      out.print(ClearLine)
      // 2. Print the log content with a guaranteed terminating newline.
      out.print(text)
      if !text.endsWith("\n") then out.println()
      // 3. Redraw the status one row below the just-written log.
      drawStatus()
      out.flush()

  /** Show (or relabel) the status line. Idempotent; calling twice with the same
    * label just refreshes the frame.
    *
    * When `animated = false` this is a no-op — non-TTY output has no persistent
    * bottom line, and the event log will have already shown the same label as a
    * `▶ <stage>` entry, so duplicating it inline would just create noise.
    */
  def startStatus(label: String = DefaultLabel): Unit =
    if animated then
      currentLabel = if label.isEmpty then DefaultLabel else label
      drawStatus()
      out.flush()

  /** Hide the status line entirely. The cursor lands at the start of the
    * (now-cleared) status row, so subsequent writes start there.
    */
  def stopStatus(): Unit =
    val wasShown = currentLabel != null
    currentLabel = null
    if wasShown && animated then
      out.print(ClearLine)
      out.flush()

  /** Advance the spinner frame and redraw. No-op when no status is set so idle
    * periods don't touch the terminal. The animator fork in
    * [[TerminalInteraction.start]] drives this via the enclosing actor.
    */
  def tick(): Unit =
    if animated && currentLabel != null then
      frameIndex = (frameIndex + 1) % Frames.size
      drawStatus()
      out.flush()

  private def drawStatus(): Unit =
    val label = currentLabel
    if label != null && animated then
      val frame = Frames(frameIndex)
      // Truncate to a single physical row's worth so the redraw on
      // each spinner tick stays anchored to one line. Without this,
      // a long stage name wraps the terminal, the next clear-line
      // only erases the wrapped tail, and the user sees a stack of
      // partial spinner frames marching down the screen.
      val truncated = Text.oneLine(label, MaxStatusLineWidth - frame.length - 2)
      out.print(ClearLine)
      out.print(paint(s"$frame $truncated", useColor))

private[terminal] object StatusBar:

  /** Carriage return + ANSI Erase-In-Line-2 (clear entire line). The ESC
    * character is the literal byte ``; writing it inline keeps the source
    * readable while preserving the binary value across tool round-trips.
    */
  private val ClearLine: String = "\r[2K"

  /** Default label when callers don't supply a more specific one. */
  private val DefaultLabel: String = "Thinking..."

  val Frames: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  /** Maximum characters in the rendered status line before we truncate. Picked
    * to fit a typical 80-column terminal without wrapping; narrower terminals
    * will wrap once on the truncation boundary, which still avoids the
    * staircase-spinner bug. Querying the real terminal width would be cleaner
    * but requires JLine's terminal API and adds startup cost for marginal
    * payoff.
    */
  private val MaxStatusLineWidth: Int = 78

  private def paint(text: String, useColor: Boolean): String =
    Ansi.paint(useColor, fansi.Color.DarkGray, text)
