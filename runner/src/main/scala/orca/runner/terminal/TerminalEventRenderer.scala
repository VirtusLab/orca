package orca.runner.terminal

import orca.events.{Announcement, OrcaEvent}

/** Renders `OrcaEvent`s — stage transitions, steps, tool uses, errors — onto a
  * [[TerminalOutputState]] and tracks the active stage stack + indent depth.
  *
  * Single-threaded: production runs it on [[TerminalActor]]'s actor, beside the
  * output state it writes to.
  */
private[terminal] class TerminalEventRenderer(
    output: TerminalOutputState,
    useColor: Boolean,
    workDir: Option[os.Path] = None
):

  import TerminalEventRenderer.{
    AssistantGlyph,
    AssistantGlyphStyle,
    CaveatGlyph,
    CaveatStyle,
    ErrorGlyph,
    MaxAssistantMessageLength,
    MaxStructuredResultRawLength,
    StageEmitters,
    StageStartGlyph,
    StepGlyphStyle,
    UserPromptGlyph,
    UserPromptStyle
  }

  // Head = most-recently-started stage.
  private var stack: List[String] = Nil

  private var stageEmitters: StageEmitters = StageEmitters.Silent

  def render(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(path) =>
      // Format at the current depth (so the marker aligns with the enclosing
      // stage's content), then push.
      val line = formatStepLine(path.name)
      stack = path.name :: stack
      stageEmitters = StageEmitters.Silent
      output.log(line)
      output.setStatus(stack.headOption)
    case _: OrcaEvent.StageEnded =>
      // `StageEnded` doesn't print: starting the next event implies the
      // previous one finished, and a failure has already printed its Error.
      stack = stack.drop(1)
      stageEmitters = StageEmitters.Silent
      output.setStatus(stack.headOption)
    case OrcaEvent.ActivityStarted(label) =>
      output.setStatus(Some(label))
    case _: OrcaEvent.ActivityEnded =>
      output.setStatus(stack.headOption)
    case OrcaEvent.ToolUse(tool, args, agent) =>
      // Recorded before the branch: an agent whose reads go out unnamed is
      // still an emitter, so its prose and writes get named once the stage has
      // several (see `attribution`).
      val who = attribution(agent)
      val line =
        // A read-only call renders as one constant line — no file, no agent —
        // so a burst from any mix of agents collapses in `TerminalOutput` (see
        // `ReadOnlyTools`); everything else shows what it acted on and who.
        if ReadOnlyTools.contains(tool) then
          ToolCallLine.format(
            ReadOnlyTools.DisplayName,
            "{}",
            paint,
            workDir,
            agent = None,
            currentIndent
          )
        else ToolCallLine.format(tool, args, paint, workDir, who, currentIndent)
      output.log(formatIndented(line))
    case _: OrcaEvent.ToolDenied =>
      () // Reported once, in the end-of-run summary.
    case _: (OrcaEvent.TokensUsed | OrcaEvent.UnpricedTurn) =>
      () // Token accounting is owned by CostTracker.
    case OrcaEvent.Step(message) =>
      output.log(formatStepLine(message))
    case _: OrcaEvent.Bookkeeping =>
      () // Not the user's work; it would read as progress. The trace keeps it.
    case _: OrcaEvent.BranchBound =>
      // Creating, checking out or resuming a branch already prints it;
      // --skip-branch stays on the user's own branch.
      ()
    case OrcaEvent.Caveat(message) =>
      // No `formatIndented`, unlike every sibling arm: it is run-scoped.
      output.log(paint(CaveatStyle, s"$CaveatGlyph ") + message)
    case OrcaEvent.StructuredResult(raw, announcement, agent) =>
      // Surfaces the result whose closing message the drain withheld in
      // structured mode. An unannounced result falls back to the raw payload,
      // collapsed and truncated, in the `●` style — ADR 0008 requires it stay
      // visible since the streamed JSON was suppressed.
      announcement match
        case Announcement.Silent => ()
        case Announcement.Say(text) =>
          val who = attribution(agent)
          output.log(
            formatStepLine(AgentAttribution.prefix(who, paint) + text)
          )
        case Announcement.Unannounced =>
          assistantLine(raw, MaxStructuredResultRawLength, attribution(agent))
            .foreach(output.log)
    case OrcaEvent.UserPrompt(text) =>
      // One line so a long prompt doesn't dominate the log; empty
      // payloads dropped.
      val collapsed = Text.oneLine(
        text,
        bodyBudget(MaxAssistantMessageLength, UserPromptGlyph, None)
      )
      if collapsed.nonEmpty then
        val glyph = paint(UserPromptStyle, s"$UserPromptGlyph ")
        output.log(formatIndented(glyph + collapsed))
    case OrcaEvent.AssistantMessage(text, agent) =>
      // One line per prose message; empty payloads (message-without-prose) dropped.
      assistantLine(text, MaxAssistantMessageLength, attribution(agent))
        .foreach(output.log)
    case OrcaEvent.Error(message, agent) =>
      // Named even when it is the stage's only emitter, unlike every other
      // arm: the line exists to say WHICH agent failed, and the stage error
      // that follows carries no name of its own.
      val glyph = paint(fansi.Color.Red, s"$ErrorGlyph ")
      val who = AgentAttribution.prefix(agent, paint)
      output.log(formatIndented(glyph + who + paint(fansi.Color.Red, message)))
    case _: OrcaEvent.SessionCommitted =>
      () // Session/manifest tracking (ADR 0021 §8) is AttemptManifestWriter's job.

  /** The current indent string. */
  def currentIndent: String = "  " * stack.length

  /** Which agent name, if any, to print on this line. While a stage has a
    * single emitter, nothing is prefixed — a stage running one agent looks
    * exactly as it did. From the moment a second agent emits, every line is
    * named, the first agent's included: with the review fan-out interleaving
    * them, an unnamed line can no longer be attributed by position. The first
    * agent's earlier lines stay bare — the log is append-only, so nothing
    * already printed can be prefixed after the fact.
    *
    * An event carrying no agent name never counts as an emitter. A read-only
    * tool line is the one that counts its emitter and then prints bare anyway:
    * it is deliberately identical whoever made the call, so that a burst of
    * them collapses ([[ReadOnlyTools]]).
    */
  private def attribution(agent: Option[String]): Option[String] =
    agent.flatMap: name =>
      stageEmitters = stageEmitters.plus(name)
      stageEmitters match
        case StageEmitters.One(_) => None
        case _                    => Some(name)

  /** What is left of `max` for a line's body once the stage indent, the glyph
    * and any agent prefix are paid for — the caps bound the rendered line, not
    * the text alone.
    */
  private def bodyBudget(
      max: Int,
      glyph: String,
      agent: Option[String]
  ): Int =
    LineBudget.remaining(
      max,
      currentIndent.length,
      LineBudget.glyphWidth(glyph),
      AgentAttribution.width(agent)
    )

  /** `text` as one `●` line capped at `max` columns, prefixed with `who`;
    * `None` when nothing is left to show.
    */
  private def assistantLine(
      text: String,
      max: Int,
      who: Option[String]
  ): Option[String] =
    val collapsed = Text.oneLine(text, bodyBudget(max, AssistantGlyph, who))
    Option.when(collapsed.nonEmpty):
      val glyph = paint(AssistantGlyphStyle, s"$AssistantGlyph ")
      formatIndented(glyph + AgentAttribution.prefix(who, paint) + collapsed)

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

private[terminal] object TerminalEventRenderer:

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

  /** Never rendered — it exists so the tests that pin ADR 0008's "no `✔` ever
    * appears in the log" invariant have the glyph to assert the absence of.
    */
  val StageDoneGlyph: String = "✔"

  val ErrorGlyph: String = "✖"
  val AssistantGlyph: String = "●"

  /** Marker for a run-level caveat ([[OrcaEvent.Caveat]]). ASCII punctuation,
    * like the approval prompt's `?`, marks a line that isn't flow progress.
    */
  val CaveatGlyph: String = "!"

  /** Marker for the prompt sent to an agent. */
  val UserPromptGlyph: String = "▸"

  /** Magenta-bold "primary content" accent shared by stages, steps, and
    * structured-result summaries. One constant so the render paths can't drift.
    */
  val StepGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On

  /** `●` prose glyph, same magenta-bold as [[StepGlyphStyle]]. */
  val AssistantGlyphStyle: fansi.Attrs = StepGlyphStyle

  /** Cyan-bold: the prompt, a rare accent. */
  val UserPromptStyle: fansi.Attrs = fansi.Color.Cyan ++ fansi.Bold.On

  /** Yellow-bold: a caution, short of the red an [[OrcaEvent.Error]] gets. */
  val CaveatStyle: fansi.Attrs = fansi.Color.Yellow ++ fansi.Bold.On

  /** Per-message cap collapsing long agent prose to one line. */
  val MaxAssistantMessageLength: Int = 100

  /** Cap for the raw-payload fallback in [[OrcaEvent.StructuredResult]] when no
    * `Announce[O]` summary was provided (ADR 0008).
    */
  val MaxStructuredResultRawLength: Int = 200
