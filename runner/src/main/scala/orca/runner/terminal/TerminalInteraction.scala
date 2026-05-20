package orca.runner.terminal

import orca.backend.{Conversation, Interaction, LlmResult}
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag
import ox.channels.{Channel, ChannelClosed}
import ox.{Ox, forkUser}

import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import scala.util.control.NonFatal

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to stderr so
  * the structured output on stdout stays clean).
  *
  * The output is split in two zones:
  *   - The **event log** at the top, growing line-by-line as stages start and
  *     tools fire.
  *   - A **status line** pinned at the bottom, showing the current activity
  *     with an animated spinner glyph.
  *
  * Both are owned by [[StatusBar]]: each event-log write transparently scrolls
  * the status row down by one. When the renderer doesn't own a TTY (CI,
  * redirected stderr, `NO_COLOR`/`ORCA_NO_ANIMATION`), the `StatusBar` degrades
  * to plain inline output without ANSI escapes.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8 default
  * charset the caller should pass a PrintStream constructed with `new
  * PrintStream(out, true, "UTF-8")`.
  *
  * Internally: a single Ox `forkUser` worker serializes every access to the
  * underlying [[TerminalRendererState]] via a `Channel[Task]` mailbox. Listener
  * events are submitted with `tell`-style fire-and-forget; `drive` submits an
  * `ask`-style request and blocks the caller until the worker finishes the
  * conversation. While `drive` runs, listener events queue and flush in order
  * after it returns — fine in practice because backend conversations emit
  * `ConversationEvent`s directly to the renderer (not through this listener
  * path) and the body thread is blocked, so no concurrent emit source exists
  * during `drive`.
  *
  * [[close]] gracefully shuts down: it signals the channel done, and the worker
  * drains any pending submissions in FIFO order before exiting. Because the
  * worker is a `forkUser`, the enclosing supervised scope waits for it to
  * complete — `flow(...)` invokes [[close]] in `finally` so the scope ends
  * cleanly. The constructor is private; obtain instances through
  * [[TerminalInteraction.start]], which takes the Ox scope where the worker
  * lives.
  */
class TerminalInteraction private[terminal] (
    mailbox: Channel[TerminalInteraction.Task]
) extends Interaction:

  import TerminalInteraction.{Run, Task}

  private val listenersList: List[OrcaListener] = List: (e: OrcaEvent) =>
    val _ = mailbox.send(Run(_.onEvent(e)))

  def listeners: List[OrcaListener] = listenersList

  /** Drive a live conversation to completion on the worker thread. Blocks the
    * caller (the flow's main thread) until the conversation finishes; queued
    * listener events behind this request will be processed afterwards.
    *
    * If the caller is interrupted while waiting, the `InterruptedException`
    * propagates — the worker continues processing the conversation to its
    * natural end (no fine-grained mid-render cancellation today; cancel via
    * `Conversation.cancel` upstream if you need that).
    */
  def drive[B <: BackendTag](conversation: Conversation[B]): LlmResult[B] =
    val reply = new CompletableFuture[LlmResult[B]]()
    val _ = mailbox.send(Run { st =>
      try
        val _ = reply.complete(st.driveConversation(conversation))
      catch
        case t: Throwable =>
          val _ = reply.completeExceptionally(t)
    })
    try reply.get()
    catch
      case e: java.util.concurrent.ExecutionException =>
        throw Option(e.getCause).getOrElse(e)

  /** Signal the worker that no more tasks will arrive. The worker drains the
    * remaining mailbox in FIFO order and then exits, which lets the enclosing
    * supervised scope (where the `forkUser` lives) complete. Idempotent —
    * subsequent calls observe the already-closed channel and return without
    * touching it.
    */
  override def close(): Unit =
    val _ = mailbox.doneOrClosed()

object TerminalInteraction:

  /** Worker mailbox task: a `TerminalRendererState => Unit` thunk. The
    * sentinel-for-stop pattern isn't needed; we rely on `Channel.done()` to
    * close the channel gracefully and let the worker drain on its own.
    */
  private[terminal] sealed trait Task
  private[terminal] final case class Run(f: TerminalRendererState => Unit)
      extends Task

  /** Build a `TerminalInteraction` and start its worker as a `forkUser` in the
    * given Ox scope. The scope waits for the worker to finish; the runtime
    * invokes [[TerminalInteraction.close]] (which closes the mailbox channel)
    * during `flow(...)`'s `finally` so the worker drains and the scope exits.
    *
    * The `(using Ox)` capability is scoped to this factory so the constructor
    * stays plain — TerminalInteraction values can be passed around without
    * dragging the Ox capability with them.
    */
  def start(
      out: PrintStream = System.err,
      useColor: Boolean = defaultUseColor,
      animated: Boolean = defaultAnimated,
      workDir: Option[os.Path] = None
  )(using Ox): TerminalInteraction =
    val state = new TerminalRendererState(out, useColor, animated, workDir)
    val mailbox: Channel[Task] = Channel.bufferedDefault[Task]
    val _ = forkUser:
      try
        var running = true
        while running do
          mailbox.receiveOrClosed() match
            case _: ChannelClosed => running = false
            case Run(f) =>
              try f(state)
              catch
                // Interrupt = wind down cleanly. Restore the interrupt flag so
                // anything else on this thread observes it.
                case _: InterruptedException =>
                  Thread.currentThread().interrupt()
                  running = false
                // Recoverable renderer-side bug — log to stderr (best-effort,
                // since stderr might be what we just failed to write to) and
                // keep draining the mailbox so the flow can proceed to a
                // clean shutdown. Fatal throwables (OOM, StackOverflow)
                // propagate and let the JVM die loudly.
                case NonFatal(t) =>
                  System.err.println(
                    s"[orca-terminal-renderer] swallowed: $t"
                  )
      catch
        // ChannelClosedException raised by receive() on a done channel is
        // expected — treated as a clean exit signal.
        case _: ox.channels.ChannelClosedException => ()
    new TerminalInteraction(mailbox)

  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"

  /** Stages, steps, and structured-result summaries share the same magenta-bold
    * glyph — the dominant accent for "primary content" in the event log. Pulled
    * into a constant so the three render paths can't drift.
    */
  val StepGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On

  /** ANSI colors default off when stderr isn't attached to a terminal (no
    * controlling console), the `NO_COLOR` convention is honoured, or we detect
    * a CI runner.
    */
  def defaultUseColor: Boolean =
    !sys.env.contains("NO_COLOR") && consolePresent && !ciDetected

  /** Animation is strictly a subset of colour — it additionally writes
    * cursor-control escapes in a tight loop, so suppressing it when we suspect
    * the output is being captured is doubly important.
    */
  def defaultAnimated: Boolean =
    defaultUseColor && !sys.env.contains("ORCA_NO_ANIMATION")

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
