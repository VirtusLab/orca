package orca.runner.terminal

import orca.llm.{BackendTag}
import orca.events.{OrcaEvent, OrcaListener}

import orca.backend.{Conversation, Interaction, LlmResult}

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
  */
class TerminalInteraction(
    out: PrintStream = System.err,
    useColor: Boolean = TerminalInteraction.defaultUseColor,
    animated: Boolean = TerminalInteraction.defaultAnimated,
    workDir: Option[os.Path] = None
) extends Interaction:

  import TerminalInteraction.*

  private val depthCounter = new StageDepth
  private val statusBar =
    new StatusBar(out, useColor = useColor, animated = animated)
  private val stages = new StageStack
  private val listener = new TerminalListener
  private val listenersList: List[OrcaListener] = List(listener)

  def listeners: List[OrcaListener] = listenersList

  /** Drive a live conversation to completion. Delegates to
    * [[TerminalConversationRenderer]] for the per-event rendering +
    * approval-prompt machinery; this class retains responsibility for the
    * ambient status bar and the `OrcaListener` surface.
    */
  def drive[B <: BackendTag](conversation: Conversation[B]): LlmResult[B] =
    new TerminalConversationRenderer(
      useColor = useColor,
      statusBar = statusBar,
      depth = depthCounter,
      workDir = workDir,
      structuredMode = conversation.outputSchema.isDefined
    ).render(conversation)

  private class TerminalListener extends OrcaListener:
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.StageStarted(name) =>
        emitStepLine(name)
        depthCounter.push()
        stages.push(name)
        showCurrentStage()
      case OrcaEvent.StageCompleted(_, _) =>
        // Stage completions don't print to the event log — starting
        // the next event implicitly tells the user the previous one
        // finished.
        depthCounter.pop()
        stages.pop()
        showCurrentStage()
      case OrcaEvent.ToolUse(tool, args) =>
        appendIndented(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
      case OrcaEvent.TokensUsed(_, _, _) =>
        () // Token accounting is owned by CostTracker.
      case OrcaEvent.Step(message) =>
        // Multi-line `message` (e.g. a wrapped review comment with
        // hanging-indented continuation lines) re-indents on each
        // newline so the body stays aligned under the glyph.
        emitStepLine(message)
      case OrcaEvent.StructuredResult(_, summary) =>
        // The conversation renderer suppresses the agent's streamed
        // JSON when in structured mode; this event is what surfaces
        // the result. We render only when an `Announce[O]` summary
        // is provided — falling back to raw JSON would just reverse
        // the suppression we did upstream. Types that want to stay
        // visible without a typeclass-driven summary should define
        // an `Announce[O]` that returns the desired text.
        summary.foreach(emitStepLine)
      case OrcaEvent.Error(message) =>
        appendIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

    /** A `▶` step line: magenta-bold glyph, neutral body. Matches the
      * assistant-prose styling (magenta `●` + neutral text) so the dominant
      * accent across the event log is consistent — stages, steps, and prose are
      * all "primary content".
      */
    private def emitStepLine(message: String): Unit =
      val glyph = paint(StepGlyphStyle, s"$StageStartGlyph ")
      appendIndented(glyph + message)

  /** Push the current innermost stage to the bar (or hide it when the stack is
    * empty). Centralised so every push/pop site does the same thing —
    * `startStatus(label)` for non-empty, `stopStatus()` for empty.
    *
    * Only the innermost stage label is shown so the bar stays compact even when
    * outer stages carry long titles (e.g. an "Implement task: <full title>"
    * wrapper). The full breadcrumb is preserved in the event log via the
    * indented `▶ <stage>` lines, so context isn't lost.
    */
  private def showCurrentStage(): Unit =
    stages.innermost match
      case Some(label) => statusBar.startStatus(label)
      case None        => statusBar.stopStatus()

  /** Append a (possibly multi-line) block to the event log, prefixing the
    * current stage indent on the first line and on every embedded `\n`. Mirrors
    * `TerminalConversationRenderer.appendBlock` so all event-log writes share
    * the same indent discipline.
    */
  private def appendIndented(text: String): Unit =
    val indent = depthCounter.contentIndent
    statusBar.appendLog(indent + text.replace("\n", "\n" + indent))

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

/** Stack of active stage names, head = most-recently-started. `innermost`
  * returns the deepest stage (the most recently pushed), which is what the
  * status bar surfaces; `None` means no stage is active. Touched only from the
  * [[TerminalListener]] thread.
  */
private class StageStack:
  private var stack: List[String] = Nil
  def push(name: String): Unit = stack = name :: stack
  def pop(): Unit = stack = stack.drop(1)
  def innermost: Option[String] = stack.headOption

object TerminalInteraction:
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
