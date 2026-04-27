package orca.runner.terminal

import java.io.PrintStream

/** A persistent single-line status indicator at the bottom of the
  * terminal, with the event log accumulating above. The status line
  * shows the current activity (stage name + spinner glyph); each log
  * write transparently scrolls it down by one row.
  *
  * Design:
  *   - All event-log output flows through [[appendLog]]. The bar
  *     clears its current status line, writes the log line, then
  *     re-draws the status below it.
  *   - The spinner runs on its own daemon thread, calling
  *     [[refreshIfCurrent]] to advance the glyph in place without
  *     touching the log.
  *   - When [[animated]] is false (non-TTY output, redirected stderr,
  *     CI), the bar degrades to plain inline output: every appendLog
  *     just writes the line, and the spinner becomes a no-op.
  *
  * Concurrency: every field is touched only under `lock`. The animator
  * thread's loop check also takes the lock to read state — which means
  * `stopStatus` must release the lock *before* joining the animator
  * (otherwise the animator's next check would deadlock waiting for the
  * lock `stopStatus` is holding). Stale-thread races (a previous
  * animator still alive when a fresh one starts) are prevented by the
  * "is this thread still the recorded animator?" identity check —
  * `animator.contains(myself)` — rather than a separate `running`
  * flag.
  */
private[terminal] class StatusBar(
    out: PrintStream,
    useColor: Boolean,
    animated: Boolean = true,
    framePeriodMs: Long = 100L
):

  import StatusBar.{ClearLine, DefaultLabel, Frames, MaxStatusLineWidth, paint}

  private val lock = new Object
  // `null` means "no status set"; non-null means "status visible at
  // the current cursor row, redraw on appendLog / refresh".
  private var currentLabel: String | Null = null
  private var frameIndex: Int = 0
  private var animator: Option[Thread] = None

  /** Append a chunk of text to the event log above the status line.
    * The chunk may contain `\n`s; trailing newline is normalised so
    * each appended chunk ends one logical row. Empty input emits
    * just the trailing newline (used by callers as a section
    * separator).
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

  /** Show (or relabel) the status line. Idempotent; calling twice
    * with the same label just refreshes the frame.
    *
    * When `animated = false` this is a no-op — non-TTY output has no
    * persistent bottom line, and the event log will have already
    * shown the same label as a `▶ <stage>` entry, so duplicating it
    * inline would just create noise.
    */
  def startStatus(label: String = DefaultLabel): Unit = lock.synchronized:
    if animated then
      val effective = if label.isEmpty then DefaultLabel else label
      currentLabel = effective
      drawStatus()
      out.flush()
      ensureAnimator()

  /** Hide the status line entirely. The cursor lands at the start of
    * the (now-cleared) status row, so subsequent writes start there.
    */
  def stopStatus(): Unit =
    val toJoin = lock.synchronized:
      val t = animator
      animator = None
      val wasShown = currentLabel != null
      currentLabel = null
      if wasShown && animated then
        out.print(ClearLine)
        out.flush()
      t
    // Join outside the lock so the animator's next loop check (which
    // takes the lock) can observe `animator == None` and exit cleanly.
    toJoin.foreach(_.join(200))

  /** Called by the animator on each tick — only redraws when this
    * thread is still the recorded animator. Stale threads (left over
    * from a stop/start race) see `animator.contains(myself) == false`
    * and exit without touching the terminal.
    */
  private def refreshIfCurrent(myself: Thread): Unit = lock.synchronized:
    if animator.contains(myself) && currentLabel != null then
      frameIndex = (frameIndex + 1) % Frames.size
      drawStatus()
      out.flush()

  private def isCurrent(myself: Thread): Boolean = lock.synchronized:
    animator.contains(myself)

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

  private def ensureAnimator(): Unit =
    if animator.isEmpty then
      val t = new Thread(() => animateLoop(), "orca-statusbar")
      t.setDaemon(true)
      animator = Some(t)
      t.start()

  private def animateLoop(): Unit =
    val myself = Thread.currentThread()
    while isCurrent(myself) do
      Thread.sleep(framePeriodMs)
      if isCurrent(myself) then refreshIfCurrent(myself)

private[terminal] object StatusBar:

  /** Carriage return + ANSI Erase-In-Line-2 (clear entire line). The
    * ESC character is the literal byte ``; writing it inline
    * keeps the source readable while preserving the binary value
    * across tool round-trips.
    */
  private val ClearLine: String = "\r[2K"

  /** Default label when callers don't supply a more specific one. */
  private val DefaultLabel: String = "Thinking..."

  val Frames: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  /** Maximum characters in the rendered status line before we truncate.
    * Picked to fit a typical 80-column terminal without wrapping;
    * narrower terminals will wrap once on the truncation boundary,
    * which still avoids the staircase-spinner bug. Querying the real
    * terminal width would be cleaner but requires JLine's terminal
    * API and adds startup cost for marginal payoff.
    */
  private val MaxStatusLineWidth: Int = 78

  private def paint(text: String, useColor: Boolean): String =
    Ansi.paint(useColor, fansi.Color.DarkGray, text)
