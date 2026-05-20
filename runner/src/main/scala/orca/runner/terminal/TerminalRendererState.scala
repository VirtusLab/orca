package orca.runner.terminal

import orca.backend.{Conversation, LlmResult}
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag

/** Holds every var-backed piece of terminal-rendering state plus the entry
  * points (`onEvent`, `driveConversation`) that mutate it.
  *
  * **Not thread-safe.** All methods must be called serially.
  * `TerminalInteraction` provides that guarantee via a dedicated worker thread;
  * tests can also call the methods directly when they only need single-threaded
  * rendering.
  */
private[orca] class TerminalRendererState(
    useColor: Boolean,
    workDir: Option[os.Path],
    statusBar: StatusBar
) extends OrcaListener:

  import TerminalInteraction.*

  private val depthCounter = new StageDepth
  private val stages = new StageStack

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      emitStepLine(name)
      depthCounter.push()
      stages.push(name)
      showCurrentStage()
    case OrcaEvent.StageCompleted(_, _) =>
      // Stage completions don't print to the event log — starting the next
      // event implicitly tells the user the previous one finished.
      depthCounter.pop()
      stages.pop()
      showCurrentStage()
    case OrcaEvent.ToolUse(tool, args) =>
      appendIndented(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
    case OrcaEvent.TokensUsed(_, _, _) =>
      () // Token accounting is owned by CostTracker.
    case OrcaEvent.Step(message) =>
      // Multi-line `message` (e.g. a wrapped review comment with
      // hanging-indented continuation lines) re-indents on each newline so the
      // body stays aligned under the glyph.
      emitStepLine(message)
    case OrcaEvent.StructuredResult(_, summary) =>
      // The conversation renderer suppresses the agent's streamed JSON when
      // in structured mode; this event is what surfaces the result. We render
      // only when an `Announce[O]` summary is provided — falling back to raw
      // JSON would just reverse the suppression we did upstream. Types that
      // want to stay visible without a typeclass-driven summary should define
      // an `Announce[O]` that returns the desired text.
      summary.foreach(emitStepLine)
    case OrcaEvent.Error(message) =>
      appendIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

  /** Advance the status-bar spinner. Called by the animator fork in
    * [[TerminalInteraction.start]] (which `tell`s the actor); routes through
    * the same actor mailbox as `onEvent` so ticks interleave cleanly with
    * other state changes.
    */
  def tickStatusBar(): Unit = statusBar.tick()

  /** Run by [[TerminalInteraction.close]] after the actor mailbox is drained.
    * Clears the status bar so any animator ticks the fork enqueues between
    * close-return and scope-end become no-ops (`tick` short-circuits when no
    * label is set).
    */
  def shutdown(): Unit = statusBar.stopStatus()

  /** Render a live conversation to completion. Used by
    * [[TerminalInteraction.drive]]; runs synchronously on the calling thread.
    */
  def driveConversation[B <: BackendTag](
      conversation: Conversation[B]
  ): LlmResult[B] =
    new TerminalConversationRenderer(
      useColor = useColor,
      statusBar = statusBar,
      depth = depthCounter,
      workDir = workDir,
      structuredMode = conversation.outputSchema.isDefined
    ).render(conversation)

  /** A `▶` step line: magenta-bold glyph, neutral body. Matches the
    * assistant-prose styling (magenta `●` + neutral text) so the dominant
    * accent across the event log is consistent — stages, steps, and prose are
    * all "primary content".
    */
  private def emitStepLine(message: String): Unit =
    val glyph = paint(StepGlyphStyle, s"$StageStartGlyph ")
    appendIndented(glyph + message)

  /** Push the current innermost stage to the bar (or hide it when the stack is
    * empty). Only the innermost stage label is shown so the bar stays compact
    * even when outer stages carry long titles — the full breadcrumb is
    * preserved in the event log via the indented `▶ <stage>` lines, so context
    * isn't lost.
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
  * status bar surfaces; `None` means no stage is active.
  *
  * Not thread-safe on its own — accessed exclusively through
  * [[TerminalRendererState]], which the worker thread serializes.
  */
private class StageStack:
  private var stack: List[String] = Nil
  def push(name: String): Unit = stack = name :: stack
  def pop(): Unit = stack = stack.drop(1)
  def innermost: Option[String] = stack.headOption
