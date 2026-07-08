package orca.backend

import orca.agents.BackendTag
import orca.util.TerminalControl

import java.util.concurrent.atomic.AtomicReference

/** Bounded-stderr diagnostics shared by the subprocess [[ForkedConversation]]s
  * (codex, gemini, pi). Keeps the last few trimmed stderr lines (capped on
  * count and bytes) so a non-zero exit / clean-exit-without-result carries the
  * real failure context in the thrown exception — listener subscribers saw each
  * line as a `ConversationEvent.Error`, but a noop listener (tests, simple
  * scripts) would otherwise lose it. Also joins the stderr drain at finalize so
  * trailing lines reach the queue before it closes.
  *
  * This trait also owns the full stderr pipeline (see [[handleStderr]]): strip
  * terminal control sequences → trim → drop known noise → surface as an `Error`
  * event → record for the bounded diagnostic buffer. The three mixing-in
  * drivers used to hand-roll this identically (and, pre-hoist, only pi stripped
  * control sequences — the other two are deliberately brought up to the same
  * behavior here); each keeps ONLY its own [[isStderrNoise]] predicate. A
  * driver needing extra teardown overrides `onFinalize` and calls
  * `super.onFinalize()`.
  */
private[orca] trait StderrPipeline[B <: BackendTag]
    extends ForkedConversation[B]:

  import StderrPipeline.*

  private val stderrBuffer = new AtomicReference[Vector[String]](Vector.empty)

  /** Backend-specific noise predicate: lines that match are dropped silently
    * instead of surfacing as a spurious `Error` event. Applied to the
    * already-stripped-and-trimmed line. Default: nothing is noise.
    */
  protected def isStderrNoise(line: String): Boolean = false

  /** Strip terminal control sequences, trim, drop [[isStderrNoise]] lines, and
    * surface anything real as both an `Error` event (`"$backendName: $line"`)
    * and a recorded diagnostic line (see [[recordStderr]]). `final` — the
    * pipeline itself is identical across backends, so subclasses vary only
    * [[isStderrNoise]].
    */
  final override protected def handleStderr(line: String): Unit =
    val trimmed = TerminalControl.stripControlSequences(line).trim
    if trimmed.nonEmpty && !isStderrNoise(trimmed) then
      eventQueue.enqueue(ConversationEvent.Error(s"$backendName: $trimmed"))
      recordStderr(trimmed)

  /** Append a trimmed, noise-filtered stderr line to the bounded buffer. */
  private def recordStderr(line: String): Unit =
    val _ = stderrBuffer.updateAndGet(appendBounded(_, line))

  /** Wait for the stderr drain so trailing lines reach the queue before the
    * failure outcome is computed. No timeout is needed: `cancel()`'s
    * `destroyForcibly` (and a real process's exit) always EOFs the stderr
    * stream, so the drain fork terminates.
    */
  override protected def onFinalize(): Unit =
    stderrDrainFork.join()

  /** Recent stderr lines as a `stderr:` block; the base owns the outer framing.
    */
  override protected def diagnosticContext: Option[String] =
    val lines = stderrBuffer.get()
    if lines.isEmpty then None
    else Some(lines.mkString("stderr:\n    ", "\n    ", ""))

private[orca] object StderrPipeline:

  /** Cap on lines kept — sized for a typical stack trace plus a brief
    * explanation, bounded so a chatty subprocess can't grow memory.
    */
  val MaxLines: Int = 20

  /** Cap on total bytes kept, for the same reason as [[MaxLines]]. */
  val MaxBytes: Int = 4096

  /** Append `line` while respecting both caps, dropping oldest first. A single
    * over-cap line is kept anyway (better than empty diagnostics). Pure, so
    * it's a safe `AtomicReference.updateAndGet` callback.
    */
  def appendBounded(buf: Vector[String], line: String): Vector[String] =
    var result = buf :+ line
    while result.size > MaxLines do result = result.tail
    while result.size > 1 && result.map(_.length).sum > MaxBytes do
      result = result.tail
    result
