package orca.runner.terminal

import ox.{Ox, forever, forkDiscard}

import java.io.PrintStream

/** A persistent single-line status indicator at the bottom of the terminal,
  * with the event log accumulating above. The status line shows the current
  * activity (stage name + spinner glyph); each log write transparently scrolls
  * it down by one row.
  *
  * Two threads touch this bar concurrently: the TerminalInteraction actor
  * worker (for `appendLog`/`startStatus`/`stopStatus`) and the animator fork
  * spawned by [[StatusBar.start]] (for `tick`). A short mutex serialises every
  * field access — the bar can't ride on the actor's serialisation alone because
  * `drive` parks the actor inside a blocking conversation iterator, which would
  * freeze the spinner whenever an interactive LLM call ran.
  *
  * When `animated = false` (non-TTY output, redirected stderr, CI), the bar
  * degrades to plain inline output: every appendLog just writes the line,
  * start/stop/tick become no-ops, and no animator fork is spawned.
  */
private[terminal] class StatusBar(
    out: PrintStream,
    useColor: Boolean,
    animated: Boolean = true
):

  import StatusBar.{ClearLine, DefaultLabel, Frames, MaxStatusLineWidth, paint}

  private val lock = new Object
  // `null` means "no status set"; non-null means "status visible at the
  // current cursor row, redraw on appendLog / tick".
  private var currentLabel: String | Null = null
  private var frameIndex: Int = 0

  /** Append a chunk of text to the event log above the status line. The chunk
    * may contain `\n`s; trailing newline is normalised so each appended chunk
    * ends one logical row. Empty input emits just the trailing newline (used by
    * callers as a section separator).
    */
  def appendLog(text: String): Unit = lock.synchronized:
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
  def startStatus(label: String = DefaultLabel): Unit = lock.synchronized:
    if animated then
      currentLabel = if label.isEmpty then DefaultLabel else label
      drawStatus()
      out.flush()

  /** Hide the status line entirely. The cursor lands at the start of the
    * (now-cleared) status row, so subsequent writes start there.
    */
  def stopStatus(): Unit = lock.synchronized:
    val wasShown = currentLabel != null
    currentLabel = null
    if wasShown && animated then
      out.print(ClearLine)
      out.flush()

  /** Advance the spinner frame and redraw. No-op when no status is set so idle
    * periods don't touch the terminal. Called by the animator fork started in
    * [[StatusBar.start]].
    */
  private def tick(): Unit = lock.synchronized:
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

  /** Build a `StatusBar` and start its animator on an Ox `forkDiscard` in the
    * given scope. The fork runs independently of the TerminalInteraction actor,
    * so the spinner continues to advance even while `drive` parks the actor
    * inside a blocking conversation iterator. The fork is interrupted when the
    * enclosing scope ends.
    */
  def start(
      out: PrintStream,
      useColor: Boolean,
      animated: Boolean,
      framePeriodMs: Long = 100L
  )(using Ox): StatusBar =
    val bar = new StatusBar(out, useColor, animated)
    if animated then
      forkDiscard:
        forever:
          Thread.sleep(framePeriodMs)
          bar.tick()
    bar

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
