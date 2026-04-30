package orca.runner.terminal

import orca.{
  Backend,
  Conversation,
  Interaction,
  LlmResult,
  OrcaEvent,
  OrcaListener
}

import java.io.PrintStream

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to
  * stderr so the structured output on stdout stays clean).
  *
  * The output is split in two zones:
  *   - The **event log** at the top, growing line-by-line as stages
  *     start and tools fire.
  *   - A **status line** pinned at the bottom, showing the current
  *     activity with an animated spinner glyph.
  *
  * Both are owned by [[StatusBar]]: each event-log write transparently
  * scrolls the status row down by one. When the renderer doesn't own
  * a TTY (CI, redirected stderr, `NO_COLOR`/`ORCA_NO_ANIMATION`), the
  * `StatusBar` degrades to plain inline output without ANSI escapes.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8
  * default charset the caller should pass a PrintStream constructed
  * with `new PrintStream(out, true, "UTF-8")`.
  */
class TerminalInteraction(
    out: PrintStream = System.err,
    useColor: Boolean = TerminalInteraction.defaultUseColor,
    animated: Boolean = TerminalInteraction.defaultAnimated,
    workDir: Option[os.Path] = None
) extends Interaction:

  import TerminalInteraction.*

  private val depthCounter = new StageDepth
  private val statusBar = new StatusBar(out, useColor = useColor, animated = animated)
  private val stages = new StageStack
  private val listener = new TerminalListener
  private val listenersList: List[OrcaListener] = List(listener)

  def listeners: List[OrcaListener] = listenersList

  /** Drive a live conversation to completion. Delegates to
    * [[TerminalConversationRenderer]] for the per-event rendering +
    * approval-prompt machinery; this class retains responsibility for
    * the ambient status bar and the `OrcaListener` surface.
    */
  def drive[B <: Backend](conversation: Conversation[B]): LlmResult[B] =
    new TerminalConversationRenderer(
      out = out,
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
        showCurrentBreadcrumb()
      case OrcaEvent.StageCompleted(_, _) =>
        // Stage completions don't print to the event log — starting
        // the next event implicitly tells the user the previous one
        // finished.
        depthCounter.pop()
        stages.pop()
        showCurrentBreadcrumb()
      case OrcaEvent.ToolUse(tool, args) =>
        appendIndented(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
      case OrcaEvent.TokensUsed(_, _) =>
        () // Token accounting is owned by CostTracker.
      case OrcaEvent.Step(message) =>
        // Multi-line `message` (e.g. a wrapped review comment with
        // hanging-indented continuation lines) re-indents on each
        // newline so the body stays aligned under the glyph.
        emitStepLine(message)
      case OrcaEvent.StructuredResult(raw, summary) =>
        // The conversation renderer suppresses the agent's raw JSON
        // when in structured mode, so this is the *only* place the
        // result lands on screen. Summary present → Step-style (▶);
        // summary absent → assistant glyph (●) so the user still
        // sees what the agent produced. The ●-styling matches
        // streamed assistant prose exactly so a fallback render is
        // visually consistent with the non-structured path.
        summary match
          case Some(s) => emitStepLine(s)
          case None    => emitAssistantLine(raw)
      case OrcaEvent.Error(message) =>
        appendIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

    /** A `▶` step line at the current stage indent. */
    private def emitStepLine(message: String): Unit =
      appendIndented(paint(fansi.Color.Cyan, s"$StageStartGlyph $message"))

    /** A `●` assistant-prose line — same glyph and styling as
      * [[TerminalConversationRenderer.AssistantGlyph]] so the
      * structured-result fallback render stays in lockstep with
      * streamed prose if either side ever changes.
      */
    private def emitAssistantLine(text: String): Unit =
      val glyph = paint(
        TerminalConversationRenderer.AssistantGlyphStyle,
        s"${TerminalConversationRenderer.AssistantGlyph} "
      )
      appendIndented(glyph + text)

  /** Push the current breadcrumb to the bar (or hide it when the
    * stack is empty). Centralised so every push/pop site does the
    * same thing — `startStatus(label)` for non-empty, `stopStatus()`
    * for empty.
    */
  private def showCurrentBreadcrumb(): Unit =
    stages.breadcrumb match
      case Some(label) => statusBar.startStatus(label)
      case None        => statusBar.stopStatus()

  /** Append a (possibly multi-line) block to the event log, prefixing
    * the current stage indent on the first line and on every embedded
    * `\n`. Mirrors `TerminalConversationRenderer.appendBlock` so all
    * event-log writes share the same indent discipline.
    */
  private def appendIndented(text: String): Unit =
    val indent = depthCounter.contentIndent
    statusBar.appendLog(indent + text.replace("\n", "\n" + indent))

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

/** Stack of active stage names, head = most-recently-started.
  * `breadcrumb` joins the stack outermost-first with `" > "` for the
  * status bar; `None` means no stage is active. Touched only from
  * the [[TerminalListener]] thread.
  */
private class StageStack:
  private var stack: List[String] = Nil
  def push(name: String): Unit = stack = name :: stack
  def pop(): Unit = stack = stack.drop(1)
  def breadcrumb: Option[String] =
    if stack.isEmpty then None
    else Some(stack.reverse.mkString(" > "))

object TerminalInteraction:
  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"

  /** ANSI colors default off when stderr isn't attached to a terminal
    * (no controlling console), the `NO_COLOR` convention is honoured,
    * or we detect a CI runner.
    */
  def defaultUseColor: Boolean =
    !sys.env.contains("NO_COLOR") && consolePresent && !ciDetected

  /** Animation is strictly a subset of colour — it additionally
    * writes cursor-control escapes in a tight loop, so suppressing it
    * when we suspect the output is being captured is doubly important.
    */
  def defaultAnimated: Boolean =
    defaultUseColor && !sys.env.contains("ORCA_NO_ANIMATION")

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
