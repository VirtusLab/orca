package orca.backend

import orca.llm.BackendTag

import java.util.concurrent.atomic.AtomicReference

/** Bounded-stderr diagnostics shared by the subprocess [[StreamConversation]]s
  * (codex, pi). Keeps the last few trimmed stderr lines (capped on count and
  * bytes) so a non-zero exit / clean-exit-without-result carries the real
  * failure context in the thrown exception — listener subscribers saw each line
  * as a `ConversationEvent.Error`, but a noop listener (tests, simple scripts)
  * would otherwise lose it. Also joins the stderr drain at finalize so trailing
  * lines reach the queue before it closes.
  *
  * The driver keeps its own [[StreamConversation.handleStderr]] (the noise
  * filter and prefix genuinely differ per backend) and calls [[recordStderr]]
  * for lines worth keeping. A driver needing extra teardown overrides
  * `onFinalize` and calls `super.onFinalize()`.
  */
private[orca] trait BufferedStderrDiagnostics[B <: BackendTag]
    extends StreamConversation[B]:

  import BufferedStderrDiagnostics.*

  private val stderrBuffer = new AtomicReference[Vector[String]](Vector.empty)

  /** Append a trimmed, noise-filtered stderr line to the bounded buffer. */
  protected def recordStderr(line: String): Unit =
    val _ = stderrBuffer.updateAndGet(appendBounded(_, line))

  /** Bounded wait for the stderr drain so trailing lines reach the queue. */
  override protected def onFinalize(): Unit =
    try stderrDrainThread.join(DrainTimeoutMs)
    catch case _: InterruptedException => Thread.currentThread().interrupt()

  /** Recent stderr lines as a `stderr:` block; the base owns the outer framing. */
  override protected def diagnosticContext: Option[String] =
    val lines = stderrBuffer.get()
    if lines.isEmpty then None
    else Some(lines.mkString("stderr:\n    ", "\n    ", ""))

private[orca] object BufferedStderrDiagnostics:
  /** Bounded wait for the stderr drain thread at finalize. */
  val DrainTimeoutMs: Long = 500L

  /** Cap on lines kept — sized for a typical stack trace plus a brief
    * explanation, bounded so a chatty subprocess can't grow memory.
    */
  val MaxLines: Int = 20

  /** Cap on total bytes kept, for the same reason as [[MaxLines]]. */
  val MaxBytes: Int = 4096

  /** Append `line` while respecting both caps, dropping oldest first. A single
    * over-cap line is kept anyway (better than empty diagnostics). Pure, so it's
    * a safe `AtomicReference.updateAndGet` callback.
    */
  def appendBounded(buf: Vector[String], line: String): Vector[String] =
    var result = buf :+ line
    while result.size > MaxLines do result = result.tail
    while result.size > 1 && result.map(_.length).sum > MaxBytes do
      result = result.tail
    result
