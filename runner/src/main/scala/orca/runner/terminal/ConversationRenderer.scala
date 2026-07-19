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
import ox.Ox

/** Renders a [[Conversation]] to the terminal: the user's opening prompt sits
  * on its own section, and each tool call/result gets a compact one-line
  * glyph-tagged summary. Assistant prose is NOT rendered here — see the
  * `render` scaladoc.
  *
  * All writes go through the shared [[TerminalOutput]] so the persistent status
  * row isn't torn by ad-hoc prints and this renderer shares one surface with
  * [[TerminalEventListener]]. Constructed per conversation on the caller
  * thread; its state doesn't escape. `output.prompt` around the
  * approval/question prompts keeps live event output from scribbling on top of
  * `readLine`.
  *
  * A small section state machine controls spacing: consecutive tool events stay
  * tight, but a prose↔tool transition gets exactly one blank-line separator.
  */
private[terminal] class ConversationRenderer(
    useColor: Boolean,
    output: TerminalOutput,
    currentIndent: () => String,
    workDir: Option[os.Path] = None,
    prompter: ConversationRenderer.Prompter = ConversationRenderer.JLinePrompter
):

  import ConversationRenderer.*

  private var currentSection: Section = Section.None

  /** Render the conversation to completion. The `Either` from `awaitResult()`
    * is passed through so the caller decides whether to throw or surface the
    * cancellation as a value.
    *
    * Assistant prose never reaches this renderer directly: `AgentCall`
    * withholds it upstream (`Conversations.withholdInteractiveProse`) and
    * re-surfaces it as `OrcaEvent.AssistantMessage` /
    * `OrcaEvent.StructuredResult`, which `TerminalEventListener` renders — the
    * same channel the autonomous path uses. This renderer only ever sees tool
    * calls/results, approvals, questions, errors, and the user's own messages.
    */
  def render[B <: BackendTag](
      conversation: Conversation[B]
  )(using Ox): Either[OrcaInteractiveCancelled, AgentResult[B]] =
    conversation.events.foreach(dispatch(_, conversation))
    conversation.awaitResult()

  private def dispatch[B <: BackendTag](
      event: ConversationEvent,
      conversation: Conversation[B]
  ): Unit = event match
    case ConversationEvent.UserMessage(text)     => renderUserMessage(text)
    case ConversationEvent.AssistantTextDelta(_) =>
      // Never reaches here in production (see the `render` scaladoc); a
      // renderer driven directly against a raw, un-withheld `Conversation`
      // (as some tests do) simply drops prose rather than rendering it twice
      // once `Interaction.listeners`' `TerminalEventListener` also does.
      ()
    case ConversationEvent.AssistantThinkingDelta(_) => ()
    case ConversationEvent.AssistantToolCall(name, input) =>
      renderToolCall(name, input)
    case ConversationEvent.ToolResult(_, ok, content) =>
      renderToolResult(ok, content)
    case ConversationEvent.AssistantTurnEnd => ()
    case ConversationEvent.Error(message)   => renderError(message)
    case ConversationEvent.ApproveTool(name, input, respond) =>
      promptApproval(name, input, respond, conversation)
    case ConversationEvent.UserQuestion(question, respond) =>
      promptUserQuestion(question, respond, conversation)

  // --- Rendering ---

  private def renderUserMessage(text: String): Unit =
    enterSection(Section.Prose)
    val header = paint(UserHeaderStyle, s"$UserGlyph you")
    // One truncated line: the initial message is usually a full templated
    // instruction that would otherwise dominate the log.
    val body = paint(
      UserBodyStyle,
      bulletIndent(Text.oneLine(text, MaxUserMessageLength))
    )
    appendBlock(s"$header\n$body")

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

  /** Insert a blank-line separator when crossing a section boundary, then
    * update the section. No-op within a section or before anything is emitted.
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
    // `prompt` suspends the status and buffers concurrent log tells so the
    // readline lands cleanly, then drains/redraws on the way out.
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

  /** Inset prose under a header glyph by 2 spaces. The outer stage-depth indent
    * is added later by [[appendBlock]].
    */
  private def bulletIndent(text: String): String =
    text.linesIterator.map(l => s"  $l").mkString("\n")

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[terminal] object ConversationRenderer:
  val MaxInlineInputLength: Int = 120
  // Enough of a large file read / command output to signal "something
  // happened" without wrapping past one line.
  val MaxInlineContentLength: Int = 100
  val MaxUserMessageLength: Int = 100

  val UserGlyph: String = "▸"
  val ToolCallGlyph: String = "⏺"
  val ToolResultGlyph: String = "⎿"
  val ToolErrorGlyph: String = "✖"
  val ErrorGlyph: String = "✖"
  val ApprovalGlyph: String = "?"

  // Palette: magenta-bold is the dominant "primary content" accent (stages,
  // steps, assistant prose); tool calls are yellow-bold so "doing something
  // external" stands apart; secondary text (tool args/results) is dark-gray;
  // user prompts keep cyan as their distinctive, rare accent.
  val UserHeaderStyle: fansi.Attrs = fansi.Color.Cyan ++ fansi.Bold.On
  val UserBodyStyle: fansi.Attrs = fansi.Color.Cyan
  val AssistantTextStyle: fansi.Attrs = fansi.Attrs.Empty
  val ToolNameStyle: fansi.Attrs = fansi.Color.Yellow ++ fansi.Bold.On
  val ToolArgsStyle: fansi.Attrs = fansi.Color.DarkGray
  val ToolResultStyle: fansi.Attrs = fansi.Color.DarkGray
  val ErrorStyle: fansi.Attrs = fansi.Color.Red
  val ApprovalStyle: fansi.Attrs = fansi.Color.Yellow

  private[terminal] enum Section:
    case None, Prose, Tool

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

    /** Release any I/O resources the prompter acquired. Called once at
      * interaction teardown, never per conversation. Default is a no-op.
      */
    def close(): Unit = ()

  /** Default production prompter: JLine line reader. Lazy so the terminal is
    * only opened when an approval prompt fires — non-interactive sessions never
    * allocate one.
    *
    * Limitation: process-scoped and its lazy terminal cannot re-initialize
    * after `close()`, so a second `flow(...)` in the same JVM that fires a
    * prompt is unsupported. Inject a custom [[Prompter]] for embedded/multi-run
    * scenarios.
    */
  object JLinePrompter extends Prompter:
    // `opened` records a SUCCESSFUL build (set inside the lazy-init lock, after
    // build() returns) so close() never forces the lazy terminal and a failed
    // build leaves nothing to close. @volatile for the close()-thread read.
    @volatile private var opened = false
    private lazy val terminal: Terminal =
      val t = TerminalBuilder.builder().system(true).dumb(true).build()
      opened = true
      t
    private lazy val reader: LineReader =
      LineReaderBuilder.builder().terminal(terminal).build()

    def ask(prompt: String): PromptOutcome =
      // Ctrl-C (UserInterrupt) and Ctrl-D / closed-stdin (EndOfFile, also hit by
      // a headless run reaching an ask-user prompt with no tty) both mean "the
      // user isn't answering": map both to Interrupted rather than let
      // EndOfFileException escape as a message-less stage failure.
      try PromptOutcome.Answer(reader.readLine(prompt))
      catch
        case _: (UserInterruptException | EndOfFileException) =>
          PromptOutcome.Interrupted

    override def close(): Unit = if opened then terminal.close()
