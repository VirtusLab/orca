package orca.runner.terminal

import orca.llm.{BackendTag}
import orca.backend.{
  ApprovalDecision,
  Conversation,
  ConversationEvent,
  LlmResult
}
import org.jline.reader.{LineReader, LineReaderBuilder, UserInterruptException}
import org.jline.terminal.TerminalBuilder

import scala.util.control.NonFatal

/** Renders a [[Conversation]] to the terminal. The layout aims for a
  * Claude-Code-like aesthetic: the user's opening prompt sits on its own
  * section, the agent's prose flushes as a single block at each turn boundary,
  * and each tool call/result gets a compact one-line summary tagged with a
  * glyph.
  *
  * All event-log writes flow through the shared [[StatusBar]] so the persistent
  * status row at the bottom doesn't get torn by ad-hoc `print` calls. The
  * renderer accepts a `StatusBar` directly (rather than constructing one) so it
  * shares the bar with [[TerminalInteraction]]'s stage listener — a single bar,
  * one set of cursor escapes.
  *
  * Spacing is controlled by a small section state machine — consecutive tool
  * events don't grow blank lines between them, but a transition from prose to a
  * tool block (or back) gets exactly one separator.
  *
  * The renderer is single-use per conversation; the JLine terminal is closed on
  * the way out. Approval prompts catch `UserInterruptException` and cancel the
  * conversation instead of killing the JVM.
  */
private[terminal] class TerminalConversationRenderer(
    useColor: Boolean,
    statusBar: StatusBar,
    depth: StageDepth,
    workDir: Option[os.Path] = None,
    showThinking: Boolean = false,
    /** When non-empty the conversation is in structured-output mode — the
      * agent's final assistant text is the JSON payload the library will
      * deserialize and surface via `OrcaEvent.StructuredResult`. We swallow the
      * streamed text to avoid showing the raw JSON twice (once mid-stream as
      * `●`, once via the structured-result event); the listener decides what to
      * render based on whether an `Announce[O]` summary is available.
      */
    structuredMode: Boolean = false,
    prompter: TerminalConversationRenderer.Prompter =
      TerminalConversationRenderer.JLinePrompter
):

  import TerminalConversationRenderer.*

  /** Section-spacing state. Consecutive `Tool` events stay tight; a `Tool →
    * Prose` (or vice versa) transition inserts a blank line.
    */
  private var currentSection: Section = Section.None

  /** Buffer for assistant text in the current turn. Flushed as a single block
    * at `AssistantTurnEnd` so the prose lands together rather than
    * delta-by-delta. In structured-output mode the buffer is dropped instead of
    * flushed — see the `structuredMode` constructor doc.
    */
  private val textBuffer = new StringBuilder

  /** Per-turn glyph + style we'll prepend to the buffered text when we flush.
    * Captured at the first delta so we don't lose the styling between buffering
    * and flush.
    */
  private var pendingProseStyling: Option[ProseStyling] = None

  /** Explicit Either→exception boundary. The flow's stage machinery propagates
    * failures via exceptions, so a user cancel is rethrown as
    * [[OrcaInteractiveCancelled]] for the enclosing `stage(...)` to handle. The
    * conversation API stays honest (Either) at its layer; the renderer is the
    * converter.
    */
  def render[B <: BackendTag](conversation: Conversation[B]): LlmResult[B] =
    try
      conversation.events.foreach(dispatch(_, conversation))
      // A well-behaved backend ends each turn with AssistantTurnEnd,
      // which already flushes; this is a safety net for sessions that
      // close without one (e.g. cancellation mid-turn).
      flushBufferedText()
      conversation.awaitResult() match
        case Right(result)   => result
        case Left(cancelled) => throw cancelled
    finally closePrompter()

  private def dispatch[B <: BackendTag](
      event: ConversationEvent,
      conversation: Conversation[B]
  ): Unit = event match
    case ConversationEvent.UserMessage(text) => renderUserMessage(text)
    case ConversationEvent.AssistantTextDelta(text) =>
      bufferText(text, AssistantGlyph, AssistantGlyphStyle, AssistantTextStyle)
    case ConversationEvent.AssistantThinkingDelta(text) =>
      if showThinking then
        bufferText(text, ThinkingGlyph, ThinkingStyle, ThinkingStyle)
    case ConversationEvent.AssistantToolCall(name, input) =>
      renderToolCall(name, input)
    case ConversationEvent.ToolResult(_, ok, content) =>
      renderToolResult(ok, content)
    case ConversationEvent.AssistantTurnEnd => flushBufferedText()
    case ConversationEvent.Error(message)   => renderError(message)
    case ConversationEvent.ApproveTool(name, input, respond) =>
      promptApproval(name, input, respond, conversation)

  // --- Rendering ---

  private def renderUserMessage(text: String): Unit =
    enterSection(Section.Prose)
    val header = paint(UserHeaderStyle, s"$UserGlyph you")
    val body = paint(UserBodyStyle, bulletIndent(text))
    appendBlock(s"$header\n$body")

  private def bufferText(
      text: String,
      glyph: String,
      glyphStyle: fansi.Attrs,
      textStyle: fansi.Attrs
  ): Unit =
    if pendingProseStyling.isEmpty then
      pendingProseStyling = Some(ProseStyling(glyph, glyphStyle, textStyle))
    val _ = textBuffer.append(text)

  private def renderToolCall(name: String, input: String): Unit =
    enterSection(Section.Tool)
    val args = ToolInputSummary.summarise(input, MaxInlineInputLength, workDir)
    val head = paint(ToolNameStyle, s"$ToolCallGlyph $name")
    val tail = if args.isEmpty then "" else " " + paint(ToolArgsStyle, args)
    appendBlock(head + tail)

  private def renderToolResult(ok: Boolean, content: String): Unit =
    enterSection(Section.Tool)
    val glyph = if ok then ToolResultGlyph else ToolErrorGlyph
    val style = if ok then ToolResultStyle else ErrorStyle
    appendBlock(
      paint(style, s"  $glyph ${Text.oneLine(content, MaxInlineContentLength)}")
    )

  private def renderError(message: String): Unit =
    enterSection(Section.Prose)
    appendBlock(paint(ErrorStyle, s"$ErrorGlyph $message"))

  /** Render the buffered text as a single prose block. In structured-output
    * mode the buffer is dropped — the structured payload arrives via
    * `OrcaEvent.StructuredResult` instead, so showing the raw JSON inline would
    * just duplicate. Either way, `pendingProseStyling` and the buffer are
    * cleared so an empty-buffer turn doesn't leak state.
    */
  private def flushBufferedText(): Unit =
    val styling = pendingProseStyling.getOrElse(
      ProseStyling(AssistantGlyph, AssistantGlyphStyle, AssistantTextStyle)
    )
    pendingProseStyling = None
    if textBuffer.isEmpty then ()
    else if structuredMode then
      // Drop. The `StructuredResult` event will carry the canonical
      // text and the listener decides what to render.
      textBuffer.clear()
    else
      val text = textBuffer.toString
      textBuffer.clear()
      enterSection(Section.Prose)
      val rendered = paint(styling.glyphStyle, s"${styling.glyph} ") +
        paint(styling.textStyle, text)
      appendBlock(rendered)

  /** Insert a blank-line separator when crossing a section boundary, then
    * update the section. No-op when staying in the same kind of section or when
    * nothing has been emitted yet.
    */
  private def enterSection(next: Section): Unit =
    if currentSection != Section.None && currentSection != next then
      appendBlock("")
    currentSection = next

  // --- Indented log writes ---

  /** Append a self-contained block (one or more lines) to the event log under
    * the current stage indent. Embedded `\n`s are re-indented so multi-line
    * content stays aligned with the leading glyph.
    */
  private def appendBlock(s: String): Unit =
    val indented =
      depth.contentIndent + s.replace("\n", "\n" + depth.contentIndent)
    statusBar.appendLog(indented)

  // --- Prompts ---

  private def promptApproval[B <: BackendTag](
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit,
      conversation: Conversation[B]
  ): Unit =
    enterSection(Section.Prose)
    val summary =
      ToolInputSummary.summarise(rawInput, MaxInlineInputLength, workDir)
    appendBlock(
      paint(ApprovalStyle, s"$ApprovalGlyph $toolName requested: $summary")
    )
    // Clear the status bar before the readline so the question and
    // answer aren't visually competing with the spinner.
    statusBar.stopStatus()
    prompter.ask(
      depth.contentIndent + paint(ApprovalStyle, "  [y]es / [n]o ? ")
    ) match
      case PromptOutcome.Answer(reply) => respond(decisionFor(reply))
      case PromptOutcome.Interrupted   => conversation.cancel()

  private def decisionFor(reply: String): ApprovalDecision =
    val normalised = reply.trim.toLowerCase
    if normalised.startsWith("y") then ApprovalDecision.Allow()
    else
      ApprovalDecision.Deny(
        Some(s"user denied via terminal (answered '$normalised')")
      )

  // --- Helpers ---

  private def closePrompter(): Unit =
    try prompter.close()
    catch case NonFatal(_) => ()

  /** Inset prose under a header glyph by 2 spaces. Operates on the raw text —
    * the outer stage-depth indent is added later by [[appendBlock]].
    */
  private def bulletIndent(text: String): String =
    text.linesIterator.map(l => s"  $l").mkString("\n")

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[terminal] object TerminalConversationRenderer:
  val MaxInlineInputLength: Int = 120
  // Tool results are large file reads or command output; show just
  // enough for "something happened" without wrapping past one line.
  val MaxInlineContentLength: Int = 100

  val UserGlyph: String = "▸"
  val AssistantGlyph: String = "●"
  val ThinkingGlyph: String = "·"
  val ToolCallGlyph: String = "⏺"
  val ToolResultGlyph: String = "⎿"
  val ToolErrorGlyph: String = "✖"
  val ErrorGlyph: String = "✖"
  val ApprovalGlyph: String = "?"

  // Palette: magenta-bold glyphs are the dominant accent (stages,
  // steps, assistant prose) — they pop against neutral body text
  // without the previous wash of cyan/blue. Tool calls move to
  // yellow-bold so the "the agent is doing something external"
  // signal stands out from the magenta-bold "primary content"
  // signal. Secondary text (tool args, tool results, thinking) all
  // stays dark-gray. User prompts keep cyan as their distinctive
  // colour since they're rare and want to be visually anchored at
  // the top of an interactive session.
  val UserHeaderStyle: fansi.Attrs = fansi.Color.Cyan ++ fansi.Bold.On
  val UserBodyStyle: fansi.Attrs = fansi.Color.Cyan
  val AssistantGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On
  val AssistantTextStyle: fansi.Attrs = fansi.Attrs.Empty
  val ThinkingStyle: fansi.Attrs = fansi.Color.DarkGray
  val ToolNameStyle: fansi.Attrs = fansi.Color.Yellow ++ fansi.Bold.On
  val ToolArgsStyle: fansi.Attrs = fansi.Color.DarkGray
  val ToolResultStyle: fansi.Attrs = fansi.Color.DarkGray
  val ErrorStyle: fansi.Attrs = fansi.Color.Red
  val ApprovalStyle: fansi.Attrs = fansi.Color.Yellow

  private[terminal] enum Section:
    case None, Prose, Tool

  /** Styling captured for the buffered text we'll flush at TurnEnd. */
  private[terminal] case class ProseStyling(
      glyph: String,
      glyphStyle: fansi.Attrs,
      textStyle: fansi.Attrs
  )

  /** Outcome of a readline-style prompt. */
  enum PromptOutcome:
    case Answer(reply: String)
    case Interrupted

  /** Seam for the approval prompt. Tests inject a stub so they can assert
    * prompt text and feed scripted replies; production uses the JLine-backed
    * implementation below.
    */
  trait Prompter:
    def ask(prompt: String): PromptOutcome
    def close(): Unit

  /** Default production prompter: JLine line reader. Lazy so the terminal is
    * only opened when an approval prompt actually fires — pure non-interactive
    * sessions never allocate a terminal.
    */
  object JLinePrompter extends Prompter:
    private lazy val terminal =
      TerminalBuilder.builder().system(true).dumb(true).build()
    private lazy val reader: LineReader =
      LineReaderBuilder.builder().terminal(terminal).build()

    def ask(prompt: String): PromptOutcome =
      try PromptOutcome.Answer(reader.readLine(prompt))
      catch case _: UserInterruptException => PromptOutcome.Interrupted

    def close(): Unit = terminal.close()
