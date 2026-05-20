package orca.runner.terminal

import orca.backend.{Conversation, Interaction, LlmResult}
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag
import ox.{Ox, forever, forkDiscard}
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.io.PrintStream

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
  * Every access to the underlying [[TerminalRendererState]] is serialised on a
  * single worker thread. Listener events are fire-and-forget; `drive` blocks
  * the caller until the conversation finishes. Errors from `drive` come back to
  * the caller; any other rendering error fails the flow.
  *
  * [[close]] is called from `flow(...)`'s `finally` to drain the mailbox and
  * tear down the status bar before the enclosing scope ends.
  */
class TerminalInteraction private[terminal] (
    actor: ActorRef[TerminalRendererState]
) extends Interaction:

  val listeners: List[OrcaListener] = List: (e: OrcaEvent) =>
    actor.tell(_.onEvent(e))

  /** Drive a live conversation to completion on the worker thread. Blocks the
    * caller (the flow's main thread) until the conversation finishes; queued
    * listener events behind this request will be processed afterwards.
    *
    * Backend exceptions raised inside `driveConversation` come back through
    * `ask` — they surface at the caller and the actor keeps running.
    */
  def drive[B <: BackendTag](conversation: Conversation[B]): LlmResult[B] =
    actor.ask(_.driveConversation(conversation))

  /** Flush pending events and tear down the status bar before the enclosing
    * scope ends. Asks the actor to run `shutdown` (which clears the status
    * bar); the mailbox is FIFO so every prior `tell` is processed first, and
    * any spinner ticks the animator enqueues between this call's return and
    * scope-end become no-ops (`tick` short-circuits when no label is set). A
    * throw from a queued `tell` has already propagated to the scope (closing
    * the actor) — we swallow the close-time failure so it doesn't mask the
    * original cause.
    */
  override def close(): Unit =
    try actor.ask(_.shutdown())
    catch case _: Throwable => ()

object TerminalInteraction:

  /** Build a `TerminalInteraction` and start its actor in the given Ox scope.
    * The actor's worker terminates when the scope ends; the runtime invokes
    * [[TerminalInteraction.close]] in `flow(...)`'s `finally` to flush pending
    * events before the scope joins.
    *
    * The `(using Ox)` capability is scoped to this factory so the constructor
    * stays plain — TerminalInteraction values can be passed around without
    * dragging the Ox capability with them.
    */
  def start(
      out: PrintStream = System.err,
      useColor: Boolean = defaultUseColor,
      animated: Boolean = defaultAnimated,
      workDir: Option[os.Path] = None,
      framePeriodMs: Long = 100L
  )(using Ox, BufferCapacity): TerminalInteraction =
    val statusBar = new StatusBar(out, useColor, animated)
    val state = new TerminalRendererState(useColor, workDir, statusBar)
    val actor = Actor.create(state)
    if animated then
      // Spinner ticks go through the same mailbox as listener events and
      // `drive`, so the bar can't redraw mid-write. The fork is interrupted
      // when the supervised scope ends; the IE bubbling out of `Thread.sleep`
      // is absorbed by the supervisor (scope is already winding down).
      forkDiscard:
        forever:
          Thread.sleep(framePeriodMs)
          actor.tell(_.tickStatusBar())
    new TerminalInteraction(actor)

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
