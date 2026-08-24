package orca.runner.terminal

import orca.events.{OrcaEvent, OrcaListener}

import java.util.concurrent.atomic.AtomicReference

/** Renders `OrcaEvent`s — stage transitions, steps, tool uses, errors — via a
  * [[TerminalOutput]] and tracks the active stage stack + indent depth.
  *
  * [[stack]] has a single writer: `StageStarted`/`StageCompleted` are emitted
  * only from `stage`'s thread-affine bookkeeping (R12, ADR 0018 §2.2).
  * [[ConversationRenderer]] reads [[currentIndent]] lock-free from its own
  * thread mid-`readLine`; the `@volatile` is the whole publication story.
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
    StageEmitters,
    StageStartGlyph,
    StepGlyphStyle,
    UserPromptGlyph,
    UserPromptStyle
  }

  // Head = most-recently-started stage. See class scaladoc for the
  // single-writer/@volatile synchronization story.
  @volatile private var stack: List[String] = Nil

  // Unlike `stack` this is written from the parallel agent forks too, hence the
  // atomic rather than the @volatile single-writer story above.
  private val stageEmitters =
    new AtomicReference[StageEmitters](StageEmitters.Silent)

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      // Format at the current depth (so the marker aligns with the enclosing
      // stage's content), then push.
      val line = formatStepLine(name)
      stack = name :: stack
      stageEmitters.set(StageEmitters.Silent)
      output.log(line)
      output.setStatus(stack.headOption)
    case OrcaEvent.StageCompleted(_) =>
      // Completions don't print: starting the next event implies the previous
      // one finished.
      stack = stack.drop(1)
      stageEmitters.set(StageEmitters.Silent)
      output.setStatus(stack.headOption)
    case OrcaEvent.ToolUse(tool, args, agent) =>
      val line = formatIndented(
        ToolCallLine.format(tool, args, paint, workDir, attribution(agent))
      )
      output.log(line)
    case _: OrcaEvent.TokensUsed =>
      () // Token accounting is owned by CostTracker.
    case OrcaEvent.Step(message) =>
      output.log(formatStepLine(message))
    case OrcaEvent.StructuredResult(raw, summary) =>
      // Surfaces the result the conversation renderer suppressed in structured
      // mode. `summary` is tri-state (see the event's scaladoc): `Some(s)`
      // renders as a `▶` step; `Some("")` renders nothing (the call site
      // narrates the outcome itself); `None` falls back to the raw payload,
      // collapsed and truncated, in the `●` style — ADR 0008 requires an
      // unannounced result stay visible since the streamed JSON was suppressed.
      summary match
        case Some("") => ()
        case Some(s)  => output.log(formatStepLine(s))
        case None =>
          val collapsed = Text.oneLine(raw, MaxStructuredResultRawLength)
          val glyph = paint(AssistantGlyphStyle, s"$AssistantGlyph ")
          output.log(formatIndented(glyph + collapsed))
    case OrcaEvent.UserPrompt(text) =>
      // One line so a long task description doesn't dominate the log; empty
      // payloads dropped.
      val collapsed = Text.oneLine(text, MaxAssistantMessageLength)
      if collapsed.nonEmpty then
        val glyph = paint(UserPromptStyle, s"$UserPromptGlyph ")
        output.log(formatIndented(glyph + collapsed))
    case OrcaEvent.AssistantMessage(text, agent) =>
      // One line per prose turn; empty payloads (turn-without-prose) dropped.
      val collapsed = Text.oneLine(text, MaxAssistantMessageLength)
      if collapsed.nonEmpty then
        val glyph = paint(AssistantGlyphStyle, s"$AssistantGlyph ")
        val who = AgentAttribution.prefix(attribution(agent), paint)
        output.log(formatIndented(glyph + who + collapsed))
    case OrcaEvent.Error(message) =>
      output.log(
        formatIndented(paint(fansi.Color.Red, s"$ErrorGlyph $message"))
      )
    case _: OrcaEvent.SessionCommitted =>
      () // Session/manifest tracking (ADR 0021 §8) is RunManifestWriter's job.

  /** The current indent string. Lock-free read of the `@volatile` [[stack]]. */
  def currentIndent: String = "  " * stack.length

  /** Which agent name, if any, to print on this line. While a stage has a
    * single emitter, nothing is prefixed — a stage running one agent looks
    * exactly as it did. From the moment a second agent emits, every line is
    * named, the first agent's included: with the review fan-out interleaving
    * them, an unnamed line can no longer be attributed by position. The first
    * agent's earlier lines stay bare — the log is append-only, so nothing
    * already printed can be prefixed after the fact.
    *
    * An event carrying no agent name never counts as an emitter.
    */
  private def attribution(agent: Option[String]): Option[String] =
    agent.flatMap: name =>
      stageEmitters.updateAndGet(_.plus(name)) match
        case StageEmitters.One(_) => None
        case _                    => Some(name)

  /** A `▶` step line: magenta-bold glyph, neutral body — matching the
    * assistant-prose styling so the "primary content" accent stays consistent.
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

  /** The agents that have emitted a display event in the current stage, to the
    * precision `attribution` needs: none, exactly one (named), or several.
    */
  private enum StageEmitters:
    case Silent
    case One(name: String)
    case Many

    def plus(emitter: String): StageEmitters = this match
      case Silent                 => One(emitter)
      case One(n) if n == emitter => this
      case _                      => Many

  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"
  val AssistantGlyph: String = "●"

  /** Marker for the human input sent to the agent. Matches the
    * [[ConversationRenderer]]'s `▸` user glyph.
    */
  val UserPromptGlyph: String = "▸"

  /** Magenta-bold "primary content" accent shared by stages, steps, and
    * structured-result summaries. One constant so the render paths can't drift.
    */
  val StepGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On

  /** `●` prose glyph, same magenta-bold as [[StepGlyphStyle]] — the canonical
    * assistant-prose render for both the autonomous drain and the interactive
    * door (which withholds prose upstream and re-surfaces it here; see
    * `Conversations.withholdInteractiveProse`).
    */
  val AssistantGlyphStyle: fansi.Attrs = StepGlyphStyle

  /** Cyan-bold to mirror the [[ConversationRenderer]]'s user-message header. */
  val UserPromptStyle: fansi.Attrs = fansi.Color.Cyan ++ fansi.Bold.On

  /** Per-turn cap collapsing long agent prose to one line. Matches the live
    * renderer's tool-result-content cap.
    */
  val MaxAssistantMessageLength: Int = 100

  /** Cap for the raw-payload fallback in [[OrcaEvent.StructuredResult]] when no
    * `Announce[O]` summary was provided (ADR 0008).
    */
  val MaxStructuredResultRawLength: Int = 200
