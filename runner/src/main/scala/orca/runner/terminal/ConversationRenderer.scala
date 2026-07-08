package orca.runner.terminal

import orca.OrcaInteractiveCancelled
import orca.agents.BackendTag
import orca.backend.{
  ApprovalDecision,
  Conversation,
  ConversationEvent,
  AgentResult
}
import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.{Terminal, TerminalBuilder}

/** Renders a [[Conversation]] to the terminal. The layout aims for a
  * Claude-Code-like aesthetic: the user's opening prompt sits on its own
  * section, the agent's prose flushes as a single block at each turn boundary,
  * and each tool call/result gets a compact one-line summary tagged with a
  * glyph.
  *
  * All event-log writes flow through the shared [[TerminalOutput]] so the
  * persistent status row at the bottom doesn't get torn by ad-hoc `print`
  * calls. The renderer accepts the output directly so it shares the surface
  * with [[TerminalEventListener]] — a single output, one set of cursor escapes.
  *
  * The renderer is constructed per conversation and lives on the caller (body)
  * thread — it's not shared. State (`textBuffer`, `currentSection`,
  * `pendingProseStyling`) doesn't escape this thread. Output writes are
  * fire-and-forget tells; `output.prompt` around the approval/question prompts
  * keeps live event output from scribbling on top of `readLine`.
  *
  * Spacing is controlled by a small section state machine — consecutive tool
  * events don't grow blank lines between them, but a transition from prose to a
  * tool block (or back) gets exactly one separator.
  */
private[terminal] class ConversationRenderer(
    useColor: Boolean,
    output: TerminalOutput,
    currentIndent: () => String,
    workDir: Option[os.Path] = None,
    /** When non-empty the conversation is in structured-output mode — the
      * agent's final assistant text is the JSON payload the library will
      * deserialize and surface via `OrcaEvent.StructuredResult`. We swallow the
      * streamed text to avoid showing the raw JSON twice (once mid-stream as
      * `●`, once via the structured-result event). This drop is safe only
      * because `TerminalEventListener`'s `StructuredResult` case (ADR 0008)
      * guarantees a visible result either way: the `Announce[O]` summary as `▶`
      * when available, otherwise the truncated raw payload as `●` — never
      * silence.
      */
    structuredMode: Boolean = false,
    prompter: ConversationRenderer.Prompter = ConversationRenderer.JLinePrompter
):

  import ConversationRenderer.*

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

  /** Render the conversation to completion. Returns whatever
    * `Conversation.awaitResult()` returns — the Either is passed through so the
    * caller decides whether to throw or surface the cancellation as a value.
    */
  def render[B <: BackendTag](
      conversation: Conversation[B]
  ): Either[OrcaInteractiveCancelled, AgentResult[B]] =
    conversation.events.foreach(dispatch(_, conversation))
    // The turn grammar (ForkedConversation auto-closes every completed turn)
    // guarantees each turn ends with an AssistantTurnEnd that already flushed
    // the buffer, so on the normal path this is a no-op. It only flushes for a
    // turn the stream left open — abnormal termination (cancellation/crash
    // mid-turn) the grammar permits; a safety net, not driver-slop compensation.
    flushBufferedText()
    conversation.awaitResult()

  private def dispatch[B <: BackendTag](
      event: ConversationEvent,
      conversation: Conversation[B]
  ): Unit = event match
    case ConversationEvent.UserMessage(text) => renderUserMessage(text)
    case ConversationEvent.AssistantTextDelta(text) =>
      bufferText(text, AssistantGlyph, AssistantGlyphStyle, AssistantTextStyle)
    case ConversationEvent.AssistantThinkingDelta(_) =>
      // Never rendered: showThinking (Task 12.6) was dead in production —
      // zero wiring ever set it true — and buffering thinking text into the
      // same textBuffer/pendingProseStyling assistant prose uses risked
      // mis-styling the whole turn (whichever delta kind arrived first won
      // the styling for the rest of the buffered block). Dropping the case
      // body entirely removes both the dead flag and that latent bug at
      // once, rather than adding a flush-on-style-change to keep a feature
      // nothing ever turned on.
      ()
    case ConversationEvent.AssistantToolCall(name, input) =>
      renderToolCall(name, input)
    case ConversationEvent.ToolResult(_, ok, content) =>
      renderToolResult(ok, content)
    case ConversationEvent.AssistantTurnEnd => flushBufferedText()
    case ConversationEvent.Error(message)   => renderError(message)
    case ConversationEvent.ApproveTool(name, input, respond) =>
      promptApproval(name, input, respond, conversation)
    case ConversationEvent.UserQuestion(question, respond) =>
      promptUserQuestion(question, respond, conversation)

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
    appendBlock(ToolCallLine.format(name, input, paint, workDir))

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
    output.log(Text.indentBlock(currentIndent(), s))

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
    // `prompt` suspends the status (clears the spinner row, buffers
    // concurrent log tells from other listeners) so the readline lands
    // cleanly and live events can't scribble on top of the prompt, then
    // drains/redraws on the way out — even if `respond` throws.
    output.prompt: () =>
      prompter.ask(
        currentIndent() + paint(ApprovalStyle, "  [y]es / [n]o ? ")
      ) match
        case PromptOutcome.Answer(reply) => respond(decisionFor(reply))
        case PromptOutcome.Interrupted   => conversation.cancel()

  private def promptUserQuestion[B <: BackendTag](
      question: String,
      respond: String => Unit,
      conversation: Conversation[B]
  ): Unit =
    enterSection(Section.Prose)
    appendBlock(
      paint(ApprovalStyle, s"$ApprovalGlyph ") +
        paint(AssistantTextStyle, question)
    )
    output.prompt: () =>
      prompter.ask(
        currentIndent() + paint(ApprovalStyle, "  > ")
      ) match
        case PromptOutcome.Answer(reply) => respond(reply)
        case PromptOutcome.Interrupted   => conversation.cancel()

  private def decisionFor(reply: String): ApprovalDecision =
    val normalised = reply.trim.toLowerCase
    if normalised.startsWith("y") then ApprovalDecision.Allow()
    else
      ApprovalDecision.Deny(
        Some(s"user denied via terminal (answered '$normalised')")
      )

  // --- Helpers ---

  /** Inset prose under a header glyph by 2 spaces. Operates on the raw text —
    * the outer stage-depth indent is added later by [[appendBlock]].
    */
  private def bulletIndent(text: String): String =
    text.linesIterator.map(l => s"  $l").mkString("\n")

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[terminal] object ConversationRenderer:
  val MaxInlineInputLength: Int = 120
  // Tool results are large file reads or command output; show just
  // enough for "something happened" without wrapping past one line.
  val MaxInlineContentLength: Int = 100

  val UserGlyph: String = "▸"
  val AssistantGlyph: String = "●"
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
  // signal. Secondary text (tool args, tool results) all
  // stays dark-gray. User prompts keep cyan as their distinctive
  // colour since they're rare and want to be visually anchored at
  // the top of an interactive session.
  val UserHeaderStyle: fansi.Attrs = fansi.Color.Cyan ++ fansi.Bold.On
  val UserBodyStyle: fansi.Attrs = fansi.Color.Cyan
  val AssistantGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On
  val AssistantTextStyle: fansi.Attrs = fansi.Attrs.Empty
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

    /** Release any I/O resources the prompter acquired. Process-scoped: called
      * once at interaction teardown, never per conversation. The default is a
      * no-op — a prompter that never opens anything has nothing to release.
      */
    def close(): Unit = ()

  /** Default production prompter: JLine line reader. Lazy so the terminal is
    * only opened when an approval prompt actually fires — pure non-interactive
    * sessions never allocate a terminal.
    *
    * Limitation: this object is process-scoped and its lazy terminal cannot
    * re-initialize after `close()` — a second `flow(...)` in the same JVM that
    * fires a prompt is unsupported. Inject a custom [[Prompter]] for
    * embedded/multi-run scenarios. (A resettable-holder fix is deferred; this
    * note records the boundary.)
    */
  object JLinePrompter extends Prompter:
    // Guard so close() never forces the lazy terminal: pure non-interactive
    // runs must never allocate one (that laziness is the object's whole
    // point). `opened` records a SUCCESSFUL build — set only after `build()`
    // returns, inside the lazy-init lock — so a failed build leaves nothing
    // to close and a later close() won't re-run the failed initializer. Read
    // from whatever thread calls close(); @volatile keeps that read correct.
    @volatile private var opened = false
    private lazy val terminal: Terminal =
      val t = TerminalBuilder.builder().system(true).dumb(true).build()
      opened = true
      t
    private lazy val reader: LineReader =
      LineReaderBuilder.builder().terminal(terminal).build()

    def ask(prompt: String): PromptOutcome =
      // Ctrl-C (UserInterrupt) and Ctrl-D / closed-stdin (EndOfFile — also hit
      // by a headless run that reaches an ask-user prompt with no tty) both mean
      // "the user isn't answering"; map both to the same graceful Interrupted
      // outcome rather than letting EndOfFileException escape as an opaque,
      // message-less stage failure. Not unit-tested: `reader` is a private lazy
      // val bound to the real system terminal, with no seam to inject a throwing
      // readLine — tests that need scripted outcomes stub the [[Prompter]] trait
      // instead. The two catch arms are exercised only through the live CLI.
      try PromptOutcome.Answer(reader.readLine(prompt))
      catch
        case _: (UserInterruptException | EndOfFileException) =>
          PromptOutcome.Interrupted

    override def close(): Unit = if opened then terminal.close()
