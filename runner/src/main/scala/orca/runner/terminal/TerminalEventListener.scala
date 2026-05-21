package orca.runner.terminal

import orca.events.{OrcaEvent, OrcaListener}

/** Renders `OrcaEvent`s — stage transitions, steps, tool uses, errors — via a
  * [[TerminalOutput]] and tracks the active stage stack + indent depth.
  *
  * `EventDispatcher` fans events out to listeners concurrently (see its
  * scaladoc), so this listener serialises its own mutable state (`StageStack`,
  * `StageDepth`) with `lock.synchronized`. Output writes are fire-and-forget
  * `tell`s to `TerminalOutput`, which is itself the serialisation point for
  * `out`. The lock is held for microseconds while computing indent strings and
  * updating stage state — never across an I/O call.
  *
  * [[currentIndent]] returns the indent string under the same lock, so the
  * conversation renderer (running on a different thread than this listener)
  * gets a coherent value.
  */
private[runner] class TerminalEventListener(
    output: TerminalOutput,
    useColor: Boolean
) extends OrcaListener:

  import TerminalEventListener.{ErrorGlyph, StageStartGlyph, StepGlyphStyle}

  private val lock = new Object
  private val depth = new StageDepth
  private val stages = new StageStack

  def onEvent(event: OrcaEvent): Unit = lock.synchronized:
    event match
      case OrcaEvent.StageStarted(name) =>
        emitStepLine(name)
        depth.push()
        stages.push(name)
        showCurrentStage()
      case OrcaEvent.StageCompleted(_, _) =>
        // Stage completions don't print to the event log — starting the next
        // event implicitly tells the user the previous one finished.
        depth.pop()
        stages.pop()
        showCurrentStage()
      case OrcaEvent.ToolUse(tool, args) =>
        appendIndented(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
      case OrcaEvent.TokensUsed(_, _, _) =>
        () // Token accounting is owned by CostTracker.
      case OrcaEvent.Step(message) =>
        // Multi-line `message` (e.g. a wrapped review comment with
        // hanging-indented continuation lines) re-indents on each newline so
        // the body stays aligned under the glyph.
        emitStepLine(message)
      case OrcaEvent.StructuredResult(_, summary) =>
        // The conversation renderer suppresses the agent's streamed JSON when
        // in structured mode; this event is what surfaces the result. We
        // render only when an `Announce[O]` summary is provided — falling
        // back to raw JSON would just reverse the suppression we did
        // upstream. Types that want to stay visible without a typeclass-
        // driven summary should define an `Announce[O]` that returns the
        // desired text.
        summary.foreach(emitStepLine)
      case OrcaEvent.Error(message) =>
        appendIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

  /** The current indent string. Held under the same lock as stage-stack
    * mutations, so a renderer on another thread sees a coherent snapshot.
    */
  def currentIndent: String = lock.synchronized(depth.contentIndent)

  /** A `▶` step line: magenta-bold glyph, neutral body. Matches the
    * assistant-prose styling (magenta `●` + neutral text) so the dominant
    * accent across the event log is consistent — stages, steps, and prose are
    * all "primary content".
    */
  private def emitStepLine(message: String): Unit =
    val glyph = paint(StepGlyphStyle, s"$StageStartGlyph ")
    appendIndented(glyph + message)

  /** Push the current innermost stage to the bar (or hide it when the stack is
    * empty). Only the innermost stage label is shown so the bar stays compact;
    * the full breadcrumb is preserved in the event log via the indented `▶
    * <stage>` lines.
    */
  private def showCurrentStage(): Unit =
    output.setStatus(stages.innermost)

  /** Tell the output to append a (possibly multi-line) block under the current
    * stage indent. Embedded `\n`s are re-indented so multi-line content stays
    * aligned with the leading glyph.
    */
  private def appendIndented(text: String): Unit =
    val indent = depth.contentIndent
    output.log(indent + text.replace("\n", "\n" + indent))

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[runner] object TerminalEventListener:

  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"

  /** Stages, steps, and structured-result summaries share the same magenta-
    * bold glyph — the dominant accent for "primary content" in the event log.
    * Pulled into a constant so the three render paths can't drift.
    */
  val StepGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On

/** Stack of active stage names, head = most-recently-started. `innermost`
  * returns the deepest stage (most recently pushed); `None` means no stage is
  * active.
  *
  * Not thread-safe on its own — accessed exclusively through
  * [[TerminalEventListener]], which serialises with its own lock.
  */
private class StageStack:
  private var stack: List[String] = Nil
  def push(name: String): Unit = stack = name :: stack
  def pop(): Unit = stack = stack.drop(1)
  def innermost: Option[String] = stack.headOption
