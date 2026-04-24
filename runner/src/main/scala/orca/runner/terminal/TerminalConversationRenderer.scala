package orca.runner.terminal

import orca.{
  ApprovalDecision,
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult
}
import org.jline.reader.{LineReader, LineReaderBuilder, UserInterruptException}
import org.jline.terminal.TerminalBuilder

import java.io.PrintStream
import scala.util.control.NonFatal

/** Renders a [[Conversation]] to the terminal: stream agent text/thinking
  * as it arrives, announce tool calls and results, and prompt the user
  * for approval decisions and free-form input. Designed to be single-use
  * per conversation; construct, call [[render]], then the underlying
  * JLine terminal is closed on the way out.
  *
  * The renderer intentionally does no global signal handling — a Ctrl-C
  * hit while streaming still kills the JVM today. Approval prompts,
  * which is where users most often want a graceful escape, handle
  * `UserInterruptException` from JLine and call `conversation.cancel()`
  * so the enclosing `flow` can recover.
  */
private[terminal] class TerminalConversationRenderer(
    out: PrintStream,
    useColor: Boolean,
    spinner: Option[OrcaSpinner],
    showThinking: Boolean = false,
    prompter: TerminalConversationRenderer.Prompter =
      TerminalConversationRenderer.JLinePrompter
):

  import TerminalConversationRenderer.*

  def render[B <: Backend](conversation: Conversation[B]): LlmResult[B] =
    out.println(paint(fansi.Color.Yellow, "[interactive session]"))
    // Claude's time-to-first-byte can be 10s+; spin immediately so
    // the user sees the session is alive while the agent is thinking.
    spinner.foreach(_.start("thinking"))
    try
      conversation.events.foreach(dispatch(_, conversation))
      conversation.awaitResult()
    finally closePrompter()

  private def dispatch[B <: Backend](
      event: ConversationEvent,
      conversation: Conversation[B]
  ): Unit = event match
    case ConversationEvent.AssistantTextDelta(text)     => renderText(text)
    case ConversationEvent.AssistantThinkingDelta(text) => renderThinking(text)
    case ConversationEvent.AssistantToolCall(name, input) =>
      renderToolCall(name, input)
    case ConversationEvent.ToolResult(_, ok, content) =>
      renderToolResult(ok, content)
    case ConversationEvent.AssistantTurnEnd => renderTurnEnd()
    case ConversationEvent.Error(message)   => renderError(message)
    case ConversationEvent.ApproveTool(name, input, respond) =>
      promptApproval(name, input, respond, conversation)

  // --- Rendering ---

  private def renderText(text: String): Unit =
    stopSpinner()
    out.print(paint(fansi.Color.Reset, text))
    out.flush()

  private def renderThinking(text: String): Unit =
    if showThinking then
      stopSpinner()
      out.print(paint(fansi.Color.DarkGray, text))
      out.flush()

  private def renderToolCall(name: String, input: String): Unit =
    stopSpinner()
    out.println()
    out.println(
      paint(fansi.Color.DarkGray, s"  → $name: ${summarise(input, MaxInlineInputLength)}")
    )

  private def renderToolResult(ok: Boolean, content: String): Unit =
    stopSpinner()
    val colour = if ok then fansi.Color.DarkGray else fansi.Color.Red
    val label = if ok then "←" else "✖"
    out.println(
      paint(colour, s"  $label ${summarise(content, MaxInlineContentLength)}")
    )

  private def renderTurnEnd(): Unit =
    out.println()
    // Agent may continue (tool roundtrip, follow-up turn) — restart the
    // spinner to signal "working"; the first content event will stop it.
    spinner.foreach(_.start("thinking"))

  private def renderError(message: String): Unit =
    stopSpinner()
    out.println(paint(fansi.Color.Red, s"  ✖ $message"))

  // --- Prompts ---

  private def promptApproval[B <: Backend](
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit,
      conversation: Conversation[B]
  ): Unit =
    stopSpinner()
    out.println()
    out.println(
      paint(
        fansi.Color.Yellow,
        s"? $toolName requested: ${summarise(rawInput, MaxInlineInputLength)}"
      )
    )
    prompter.ask(paint(fansi.Color.Yellow, "  [y]es / [n]o ? ")) match
      case PromptOutcome.Answer(reply) =>
        val normalised = reply.trim.toLowerCase
        if normalised.startsWith("y") then respond(ApprovalDecision.Allow())
        else
          respond(
            ApprovalDecision.Deny(
              Some(s"user denied via terminal (answered '$normalised')")
            )
          )
      case PromptOutcome.Interrupted => conversation.cancel()

  // --- Helpers ---

  private def stopSpinner(): Unit = spinner.foreach(_.stop())

  private def closePrompter(): Unit =
    try prompter.close()
    catch case NonFatal(_) => ()

  private def summarise(raw: String, maxLength: Int): String =
    val collapsed = raw.replaceAll("\\s+", " ").trim
    if collapsed.length <= maxLength then collapsed
    else s"${collapsed.take(maxLength)}…"

  private def paint(attr: fansi.EscapeAttr, text: String): String =
    if useColor then attr(text).render else text

private[terminal] object TerminalConversationRenderer:
  val MaxInlineInputLength: Int = 120
  val MaxInlineContentLength: Int = 160

  /** Outcome of a readline-style prompt. */
  enum PromptOutcome:
    case Answer(reply: String)
    case Interrupted

  /** Seam for the approval prompt. Tests inject a stub so they can
    * assert prompt text and feed scripted replies; production uses the
    * JLine-backed implementation below.
    */
  trait Prompter:
    def ask(prompt: String): PromptOutcome
    def close(): Unit

  /** Default production prompter: JLine line reader. Lazy so the
    * terminal is only opened when an approval prompt actually fires —
    * pure non-interactive sessions never allocate a terminal.
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
