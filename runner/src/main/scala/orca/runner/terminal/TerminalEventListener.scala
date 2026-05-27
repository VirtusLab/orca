package orca.runner.terminal

import orca.events.{OrcaEvent, OrcaListener}

/** Renders `OrcaEvent`s — stage transitions, steps, tool uses, errors — via a
  * [[TerminalOutput]] and tracks the active stage stack + indent depth.
  *
  * `EventDispatcher` fans events out to listeners concurrently (see its
  * scaladoc), so this listener serialises its own mutable state (`StageStack`,
  * `StageDepth`) with `lock.synchronized`. The lock guards **only** the state
  * mutation + formatted-string computation — output tells happen after the lock
  * releases, so a slow actor mailbox can't pin the lock and block body-thread
  * reads of [[currentIndent]].
  *
  * Ordering: within one `onEvent` call, the (state mutation, format) + (tell)
  * split preserves visible order — both happen on the same thread. Across
  * concurrent `onEvent` calls from different dispatcher threads, mailbox
  * arrival order matches lock-release order, which is what callers expect.
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
    StageStartGlyph,
    StepGlyphStyle
  }

  private val lock = new Object
  private val depth = new StageDepth
  private val stages = new StageStack
  // Indent string updated whenever `depth` changes — published via the
  // @volatile so [[currentIndent]] readers (the ConversationRenderer on
  // another thread) can snapshot a coherent indent without acquiring the
  // lock. Writes happen inside the lock; the volatile ensures the new
  // value is visible to lock-free readers.
  @volatile private var indentSnapshot: String = depth.contentIndent

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      val (line, status) = lock.synchronized:
        // Format the step line at the *current* depth (so the StageStarted
        // marker aligns with the enclosing stage's content), then push.
        val l = formatStepLine(name)
        depth.push()
        stages.push(name)
        indentSnapshot = depth.contentIndent
        (l, stages.innermost)
      output.log(line)
      output.setStatus(status)
    case OrcaEvent.StageCompleted(_, _) =>
      // Stage completions don't print to the event log — starting the next
      // event implicitly tells the user the previous one finished.
      val status = lock.synchronized:
        depth.pop()
        stages.pop()
        indentSnapshot = depth.contentIndent
        stages.innermost
      output.setStatus(status)
    case OrcaEvent.ToolUse(tool, args) =>
      val line = lock.synchronized:
        formatIndented(ToolCallLine.format(tool, args, paint, workDir))
      output.log(line)
    case OrcaEvent.TokensUsed(_, _, _) =>
      () // Token accounting is owned by CostTracker.
    case OrcaEvent.Step(message) =>
      // Multi-line `message` (e.g. a wrapped review comment with
      // hanging-indented continuation lines) re-indents on each newline so
      // the body stays aligned under the glyph.
      val line = lock.synchronized(formatStepLine(message))
      output.log(line)
    case OrcaEvent.StructuredResult(_, summary) =>
      // The conversation renderer suppresses the agent's streamed JSON
      // when in structured mode; this event is what surfaces the result.
      // We render only when an `Announce[O]` summary is provided —
      // falling back to raw JSON would just reverse the suppression we
      // did upstream. Types that want to stay visible without a
      // typeclass-driven summary should define an `Announce[O]` that
      // returns the desired text.
      summary.foreach: s =>
        val line = lock.synchronized(formatStepLine(s))
        output.log(line)
    case OrcaEvent.AssistantMessage(text) =>
      // Truncate to one line — the autonomous drain emits these for every
      // agent prose turn, and full text would dominate the log. Empty
      // payloads (turn-without-prose) are dropped silently.
      val collapsed = Text.oneLine(text, MaxAssistantMessageLength)
      if collapsed.nonEmpty then
        val line = lock.synchronized:
          val glyph = paint(AssistantGlyphStyle, s"$AssistantGlyph ")
          formatIndented(glyph + collapsed)
        output.log(line)
    case OrcaEvent.Error(message) =>
      val line = lock.synchronized:
        formatIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))
      output.log(line)

  /** The current indent string. Lock-free read — see [[indentSnapshot]] for the
    * publication mechanism.
    */
  def currentIndent: String = indentSnapshot

  /** A `▶` step line: magenta-bold glyph, neutral body. Matches the
    * assistant-prose styling (magenta `●` + neutral text) so the dominant
    * accent across the event log is consistent — stages, steps, and prose are
    * all "primary content". **Caller holds [[lock]].**
    */
  private def formatStepLine(message: String): String =
    val glyph = paint(StepGlyphStyle, s"$StageStartGlyph ")
    formatIndented(glyph + message)

  /** Re-indent a (possibly multi-line) block under the current stage indent —
    * first line and every embedded `\n` get the prefix. **Caller holds
    * [[lock]]** (reads `depth`).
    */
  private def formatIndented(text: String): String =
    val indent = depth.contentIndent
    indent + text.replace("\n", "\n" + indent)

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[runner] object TerminalEventListener:

  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"
  val AssistantGlyph: String = "●"

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

  /** Per-turn cap. Long agent prose collapses to one line because the
    * autonomous drain fires one event per turn and the log would otherwise fill
    * with multi-paragraph monologues. 100 characters matches the cap the live
    * renderer uses for tool-result content.
    */
  val MaxAssistantMessageLength: Int = 100

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
