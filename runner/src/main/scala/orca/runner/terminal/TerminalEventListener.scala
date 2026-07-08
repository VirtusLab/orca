package orca.runner.terminal

import orca.events.{OrcaEvent, OrcaListener}

/** Renders `OrcaEvent`s — stage transitions, steps, tool uses, errors — via a
  * [[TerminalOutput]] and tracks the active stage stack + indent depth.
  *
  * Dispatcher calls arrive sequentially per event but can come from concurrent
  * emitter threads for non-stage events. `StageStarted` / `StageCompleted` are
  * the exception: they're emitted only from `stage`'s bookkeeping
  * (`orca.Flow`), which is thread-affine by R12 (ADR 0018 §2.2) — so [[stack]]
  * has a single writer. The [[ConversationRenderer]] reads [[currentIndent]]
  * lock-free from its own thread, mid-`readLine`; the `@volatile` is the whole
  * publication story for that cross-thread read.
  */
private[runner] class TerminalEventListener(
    output: TerminalOutput,
    useColor: Boolean,
    workDir: Option[os.Path] = None
) extends OrcaListener:

  import TerminalEventListener.{
    AssistantGlyph,
    AssistantGlyphStyle,
    ErrorGlyph,
    MaxAssistantMessageLength,
    MaxStructuredResultRawLength,
    StageStartGlyph,
    StepGlyphStyle,
    UserPromptGlyph,
    UserPromptStyle
  }

  // Single-writer (stage events arrive on the one stage thread, R12),
  // multi-reader (ConversationRenderer polls the indent from its own thread
  // mid-prompt): a @volatile immutable list is the whole synchronization
  // story. Head = most-recently-started stage.
  @volatile private var stack: List[String] = Nil

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      // Format the step line at the *current* depth (so the StageStarted
      // marker aligns with the enclosing stage's content), then push.
      val line = formatStepLine(name)
      stack = name :: stack
      output.log(line)
      output.setStatus(stack.headOption)
    case OrcaEvent.StageCompleted(_) =>
      // Stage completions don't print to the event log — starting the next
      // event implicitly tells the user the previous one finished.
      stack = stack.drop(1)
      output.setStatus(stack.headOption)
    case OrcaEvent.ToolUse(tool, args) =>
      val line = formatIndented(ToolCallLine.format(tool, args, paint, workDir))
      output.log(line)
    case OrcaEvent.TokensUsed(_, _, _, _) =>
      () // Token accounting is owned by CostTracker.
    case OrcaEvent.Step(message) =>
      // Multi-line `message` (e.g. a wrapped review comment with
      // hanging-indented continuation lines) re-indents on each newline so
      // the body stays aligned under the glyph.
      output.log(formatStepLine(message))
    case OrcaEvent.StructuredResult(raw, summary) =>
      // The conversation renderer suppresses the agent's streamed JSON
      // when in structured mode; this event is what surfaces the result.
      // ADR 0008 fallback: render the `Announce[O]` summary as a `▶`
      // step when one is provided; otherwise fall back to the raw
      // payload — collapsed to one line and truncated — in the `●`
      // assistant-message style. This guarantees a visible result either
      // way, which is what makes the renderer's structured-mode
      // suppression (see `ConversationRenderer`'s `structuredMode` doc)
      // safe: a call site that never wired up an `Announce[O]` still
      // shows the agent's answer instead of silently dropping it.
      summary match
        case Some(s) => output.log(formatStepLine(s))
        case None =>
          val collapsed = Text.oneLine(raw, MaxStructuredResultRawLength)
          val glyph = paint(AssistantGlyphStyle, s"$AssistantGlyph ")
          output.log(formatIndented(glyph + collapsed))
    case OrcaEvent.UserPrompt(text) =>
      // Same one-line treatment as AssistantMessage so a long task
      // description doesn't dominate the log. Empty payloads are dropped.
      val collapsed = Text.oneLine(text, MaxAssistantMessageLength)
      if collapsed.nonEmpty then
        val glyph = paint(UserPromptStyle, s"$UserPromptGlyph ")
        output.log(formatIndented(glyph + collapsed))
    case OrcaEvent.AssistantMessage(text) =>
      // Truncate to one line — the autonomous drain emits these for every
      // agent prose turn, and full text would dominate the log. Empty
      // payloads (turn-without-prose) are dropped silently.
      val collapsed = Text.oneLine(text, MaxAssistantMessageLength)
      if collapsed.nonEmpty then
        val glyph = paint(AssistantGlyphStyle, s"$AssistantGlyph ")
        output.log(formatIndented(glyph + collapsed))
    case OrcaEvent.Error(message) =>
      output.log(
        formatIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))
      )

  /** The current indent string. Lock-free read of the `@volatile` [[stack]]. */
  def currentIndent: String = "  " * stack.length

  /** A `▶` step line: magenta-bold glyph, neutral body. Matches the
    * assistant-prose styling (magenta `●` + neutral text) so the dominant
    * accent across the event log is consistent — stages, steps, and prose are
    * all "primary content".
    */
  private def formatStepLine(message: String): String =
    val glyph = paint(StepGlyphStyle, s"$StageStartGlyph ")
    formatIndented(glyph + message)

  /** Re-indent a (possibly multi-line) block under the current stage indent —
    * first line and every embedded `\n` get the prefix.
    */
  private def formatIndented(text: String): String =
    Text.indentBlock(currentIndent, text)

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[runner] object TerminalEventListener:

  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"
  val AssistantGlyph: String = "●"

  /** Marker for the human input sent to the agent. Matches the
    * [[ConversationRenderer]]'s `▸` user glyph so autonomous and interactive
    * logs use the same accent for "this is what was asked".
    */
  val UserPromptGlyph: String = "▸"

  /** Stages, steps, and structured-result summaries share the same magenta-
    * bold glyph — the dominant accent for "primary content" in the event log.
    * Pulled into a constant so the three render paths can't drift.
    */
  val StepGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On

  /** Same magenta-bold treatment as [[StepGlyphStyle]] — `●` and `▶` are peer
    * accents on the "primary content" track. Matches the
    * [[ConversationRenderer]]'s prose glyph so autonomous and interactive logs
    * look identical when both surface agent prose.
    */
  val AssistantGlyphStyle: fansi.Attrs = StepGlyphStyle

  /** Cyan-bold to mirror the [[ConversationRenderer]]'s user-message header. */
  val UserPromptStyle: fansi.Attrs = fansi.Color.Cyan ++ fansi.Bold.On

  /** Per-turn cap. Long agent prose collapses to one line because the
    * autonomous drain fires one event per turn and the log would otherwise fill
    * with multi-paragraph monologues. 100 characters matches the cap the live
    * renderer uses for tool-result content.
    */
  val MaxAssistantMessageLength: Int = 100

  /** Cap for the raw-payload fallback in [[OrcaEvent.StructuredResult]] when no
    * `Announce[O]` summary was provided (ADR 0008). Matches the 200-char budget
    * `Flow`'s malformed-output snippet uses for the same kind of "show a
    * bounded slice of raw agent output" fallback.
    */
  val MaxStructuredResultRawLength: Int = 200
