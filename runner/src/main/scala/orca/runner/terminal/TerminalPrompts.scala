package orca.runner.terminal

import orca.agents.BackendTag
import orca.backend.{
  AgentResult,
  ApprovalDecision,
  ChannelEvent,
  ConversationEvent,
  ObservedConversation
}
import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.{Terminal, TerminalBuilder}

/** Answers an interactive turn's approval requests and questions at the
  * terminal. Everything else the turn produces — prose, tool calls, errors, the
  * opening prompt — reaches [[TerminalEventListener]] as `OrcaEvent`s, the same
  * way an autonomous turn's does.
  *
  * All writes go through the shared [[TerminalOutput]] so the persistent status
  * row isn't torn by ad-hoc prints. `output.prompt` around the prompts keeps
  * live event output from scribbling on top of `readLine`.
  */
private[terminal] class TerminalPrompts(
    useColor: Boolean,
    output: TerminalOutput,
    currentIndent: () => String,
    workDir: Option[os.Path] = None,
    prompter: TerminalPrompts.Prompter = TerminalPrompts.JLinePrompter
):

  import TerminalPrompts.*

  /** Drain the conversation to completion; see [[ObservedConversation.drain]].
    */
  def drive[B <: BackendTag](
      conversation: ObservedConversation[B]
  ): AgentResult[B] =
    conversation.drain(answer(_, conversation))

  private def answer[B <: BackendTag](
      event: ChannelEvent,
      conversation: ObservedConversation[B]
  ): Unit = event match
    case ConversationEvent.ApproveTool(name, input, respond) =>
      promptApproval(name, input, respond, conversation)
    case ConversationEvent.UserQuestion(question, respond) =>
      promptUserQuestion(question, respond, conversation)

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
      conversation: ObservedConversation[B]
  ): Unit =
    val summary = ToolInputSummary.summarise(
      rawInput,
      ToolInputSummary.MaxInlineInputLength,
      workDir
    )
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
      conversation: ObservedConversation[B]
  ): Unit =
    appendBlock(
      paint(ApprovalStyle, s"$ApprovalGlyph ") + question
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

  private def paint(attr: fansi.Attrs, text: String): String =
    Ansi.paint(useColor, attr, text)

private[terminal] object TerminalPrompts:
  val ApprovalGlyph: String = "?"

  val ApprovalStyle: fansi.Attrs = fansi.Color.Yellow

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
    * Entry is multi-line, sharing [[MultilineLineReader]] with the shell's own
    * task/goal/fork prompt (`orca.shell.ui.ConsoleUiShell.inputMultiline`): the
    * reader gets its widgets registered once at construction, and `ask` wraps
    * the read in the kitty-protocol bracket so Shift+Enter/Ctrl-C/Ctrl-D are
    * recognized on terminals that need it. The `Interrupted` mapping below is
    * unaffected — the kitty widgets throw the same exceptions this catch
    * already handles.
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
      val r = LineReaderBuilder.builder().terminal(terminal).build()
      // Continuation lines of a multi-line answer (a paste, or a literal
      // newline from MultilineLineReader.registerInsertNewlineWidget) get the
      // same minimal "… " marker as the shell's own multiline prompt, rather
      // than jline's default (which repeats the primary prompt's full text).
      r.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "… ")
      MultilineLineReader.registerAll(r)
      r

    def ask(prompt: String): PromptOutcome =
      // Ctrl-C (UserInterrupt) and Ctrl-D / closed-stdin (EndOfFile, also hit by
      // a headless run reaching an ask-user prompt with no tty) both mean "the
      // user isn't answering": map both to Interrupted rather than let
      // EndOfFileException escape as a message-less stage failure.
      try
        MultilineLineReader.withKittyKeyboardProtocol(terminal):
          PromptOutcome.Answer(reader.readLine(prompt))
      catch
        case _: (UserInterruptException | EndOfFileException) =>
          PromptOutcome.Interrupted

    override def close(): Unit = if opened then terminal.close()
