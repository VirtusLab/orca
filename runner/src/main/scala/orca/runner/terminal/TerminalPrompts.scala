package orca.runner.terminal

import orca.agents.BackendTag
import orca.backend.{
  AgentResult,
  ApprovalDecision,
  ChannelEvent,
  ConversationEvent,
  ObservedConversation
}
import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.terminal.TerminalBuilder

/** Answers an interactive turn's approval requests and questions at the
  * terminal. Everything else the turn produces — prose, tool calls, errors, the
  * opening prompt — reaches [[TerminalEventRenderer]] as `OrcaEvent`s, the same
  * way an autonomous turn's does.
  *
  * All writes go through the shared [[TerminalOutput]] so the persistent status
  * row isn't torn by ad-hoc prints. `output.prompt` around the prompts keeps
  * live event output from scribbling on top of `readLine`, and keeps a question
  * directly above its read when two turns ask at once.
  */
private[terminal] class TerminalPrompts(
    useColor: Boolean,
    output: TerminalOutput,
    currentIndent: () => String,
    workDir: Option[os.Path],
    prompter: TerminalPrompts.Prompter
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

  /** A self-contained block (one or more lines) under the current stage indent.
    * Embedded `\n`s are re-indented so multi-line content stays aligned with
    * the leading glyph.
    */
  private def indented(s: String): String =
    Text.indentBlock(currentIndent(), s)

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
    output.prompt(
      indented(
        paint(ApprovalStyle, s"$ApprovalGlyph $toolName requested: $summary")
      )
    ): () =>
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
    output.prompt(
      indented(paint(ApprovalStyle, s"$ApprovalGlyph ") + question)
    ): () =>
      prompter.ask(
        currentIndent() + paint(ApprovalStyle, "  > ")
      ) match
        case PromptOutcome.Answer(reply) => respond(reply)
        case PromptOutcome.Interrupted   => conversation.cancel()

  private def decisionFor(reply: String): ApprovalDecision =
    val normalised = reply.trim.toLowerCase
    if normalised.startsWith("y") then ApprovalDecision.Allow
    else ApprovalDecision.Deny

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
    * implementation below. `ask` is never called concurrently
    * ([[TerminalOutput.prompt]] runs one prompt at a time).
    */
  trait Prompter:
    def ask(prompt: String): PromptOutcome

  /** Default production prompter: a multiline read on a fresh JLine system
    * terminal per `ask`.
    */
  object JLinePrompter extends Prompter:
    def ask(prompt: String): PromptOutcome =
      // Nothing outlives a prompt: a run that never prompts never opens a
      // terminal, and the terminal's signal handling (Ctrl-C ends the JVM
      // without shutdown hooks) is in place only during the read. JLine allows
      // one open system terminal per JVM, which the serialised asks respect.
      val terminal = TerminalBuilder.builder().system(true).dumb(true).build()
      // Ctrl-C (UserInterrupt) and Ctrl-D / closed-stdin (EndOfFile, also hit by
      // a headless run reaching an ask-user prompt with no tty) both mean "the
      // user isn't answering": map both to Interrupted rather than let
      // EndOfFileException escape as a message-less stage failure.
      try
        PromptOutcome.Answer(
          new MultilineLineReader(terminal).readMultiline(prompt)
        )
      catch
        case _: (UserInterruptException | EndOfFileException) =>
          PromptOutcome.Interrupted
      finally terminal.close()
