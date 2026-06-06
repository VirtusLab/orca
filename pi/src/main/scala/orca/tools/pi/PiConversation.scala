package orca.tools.pi

import orca.events.Usage
import orca.llm.{BackendTag, Model, SessionId}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, LlmResult}
import orca.backend.{StreamConversation, StreamSource}
import orca.subprocess.PipedCliProcess
import orca.tools.pi.rpc.{
  AgentMessage,
  InboundEvent,
  MessageDelta,
  OutboundMessage
}

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Drives one `pi --mode rpc` process for a single Orca LLM call. The backend
  * sends one `prompt` command, this conversation translates Pi RPC events into
  * Orca conversation events, and `agent_end` becomes the terminal
  * [[LlmResult]].
  */
private[pi] class PiConversation(
    process: PipedCliProcess,
    clientSession: SessionId[BackendTag.Pi.type],
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    askUserEnabled: Boolean = false,
    resources: List[AutoCloseable] = Nil
) extends StreamConversation[BackendTag.Pi.type](
      StreamSource.fromProcess(process),
      backendName = "pi",
      initialPrompt = initialPrompt,
      nativeAskUser = askUserEnabled
    ):

  import PiConversation.*
  import StreamConversation.Outcome

  private val lastAssistantMessage = new AtomicReference[String]("")
  private val usageRef = new AtomicReference[Usage](Usage.empty)
  private val modelRef = new AtomicReference[Option[String]](None)
  private val stderrBuffer = new AtomicReference[Vector[String]](Vector.empty)

  /** Pi normally streams text deltas, but tests and future protocol variants
    * may only emit a completed message. Track whether this assistant message
    * already streamed text so message_end can act as a fallback without
    * duplicating output.
    */
  private var textDeltasSinceMessageBoundary: Boolean = false

  start()

  def sendPrompt(prompt: String): Unit =
    process.writeLine(OutboundMessage.prompt(prompt))

  /** Pi RPC prompts are command messages rather than a writable chat stdin.
    * Orca's interactive Pi support currently routes human input through the
    * ask_user extension UI bridge, so unsolicited user turns are a no-op.
    */
  def sendUserMessage(text: String): Unit = ()

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  override protected def handleStderr(line: String): Unit =
    val trimmed = stripTerminalControlSequences(line).trim
    if trimmed.nonEmpty && !isKnownStderrNoise(trimmed) then
      eventQueue.enqueue(ConversationEvent.Error(s"pi: $trimmed"))
      val _ = stderrBuffer.updateAndGet(appendBounded(_, trimmed))

  override protected def onFinalize(): Unit =
    try stderrDrainThread.join(StderrDrainTimeoutMs)
    catch case _: InterruptedException => Thread.currentThread().interrupt()
    resources.foreach(closeQuietly)

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      appendContext("pi exited cleanly but never emitted agent_end")
    )

  override protected def diagnosticContext: Option[String] =
    val lines = stderrBuffer.get()
    if lines.isEmpty then None
    else Some(lines.mkString("stderr:\n    ", "\n    ", ""))

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.Response(_, command, success, error) =>
      handleResponse(command, success, error)
    case InboundEvent.MessageUpdate(delta) => handleDelta(delta)
    case InboundEvent.MessageEnd(message)  => handleMessageEnd(message)
    case InboundEvent.AgentEnd             => handleAgentEnd()
    case InboundEvent.ToolExecutionStart(toolName, rawArgs) =>
      eventQueue.enqueue(ConversationEvent.AssistantToolCall(toolName, rawArgs))
    case InboundEvent.ToolExecutionEnd(toolName, ok, content) =>
      eventQueue.enqueue(ConversationEvent.ToolResult(toolName, ok, content))
    case InboundEvent.ExtensionUiRequest(id, method, question) =>
      handleExtensionUiRequest(id, method, question)
    case InboundEvent.Unknown(_) => ()

  private def handleResponse(
      command: Option[String],
      success: Boolean,
      error: Option[String]
  ): Unit =
    if !success then
      val message = error
        .filter(_.nonEmpty)
        .getOrElse(
          command.fold("pi RPC command failed")(c =>
            s"pi RPC command '$c' failed"
          )
        )
      eventQueue.enqueue(ConversationEvent.Error(message))
      val _ = outcomeRef.compareAndSet(
        None,
        Some(Outcome.Failed(new OrcaFlowException(message)))
      )
      process.sendSigInt()

  private def handleDelta(delta: MessageDelta): Unit = delta match
    case MessageDelta.Text(text) =>
      if text.nonEmpty then textDeltasSinceMessageBoundary = true
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
    case MessageDelta.Thinking(text) =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case MessageDelta.Other(_) => ()

  private def handleMessageEnd(message: AgentMessage): Unit =
    if message.role == "assistant" then
      message.errorMessage.foreach: error =>
        if error.nonEmpty then
          eventQueue.enqueue(ConversationEvent.Error(error))
      lastAssistantMessage.set(message.text)
      message.usage.foreach: usage =>
        val _ = usageRef.updateAndGet(_ + usage)
      message.model.foreach: model =>
        modelRef.set(Some(model))
      if message.text.nonEmpty && !textDeltasSinceMessageBoundary then
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(message.text))
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
      textDeltasSinceMessageBoundary = false

  private def handleAgentEnd(): Unit =
    val result = LlmResult(
      sessionId = clientSession,
      output = lastAssistantMessage.get(),
      usage = usageRef.get(),
      model = modelRef.get().map(Model.apply)
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))
    process.closeStdin()
    process.sendSigInt()

  private def handleExtensionUiRequest(
      id: String,
      method: String,
      question: String
  ): Unit =
    method match
      case "input" | "editor" =>
        eventQueue.enqueue(
          ConversationEvent.UserQuestion(
            question,
            answer =>
              process.writeLine(OutboundMessage.extensionUiValue(id, answer))
          )
        )
      case method if FireAndForgetUiMethods.contains(method) =>
        // Pi extensions use these for TUI decoration/status. In RPC mode they
        // are explicitly fire-and-forget, so Orca can safely ignore them.
        ()
      case other =>
        eventQueue.enqueue(
          ConversationEvent.Error(
            s"Unsupported Pi extension UI request '$other': $question"
          )
        )
        process.writeLine(OutboundMessage.extensionUiCancelled(id))

private[pi] object PiConversation:

  private val Esc: Char = 0x1b.toChar
  private val Bel: Char = 0x07.toChar
  private val Csi: Char = 0x9b.toChar
  private val Osc: Char = 0x9d.toChar
  private val Dcs: Char = 0x90.toChar
  private val Sos: Char = 0x98.toChar
  private val Pm: Char = 0x9e.toChar
  private val Apc: Char = 0x9f.toChar

  private val StderrDrainTimeoutMs: Long = 500L

  private val FireAndForgetUiMethods: Set[String] = Set(
    "notify",
    "setStatus",
    "setWidget",
    "setTitle",
    "set_editor_text"
  )

  private val StderrMaxLines: Int = 20
  private val StderrMaxBytes: Int = 4096

  private def isKnownStderrNoise(line: String): Boolean =
    // Pi's terminal notifier can write iTerm2-style notifications to stderr as
    // OSC 777 (`ESC ] 777 ; ... BEL`). We strip well-formed controls before
    // trimming, but keep this guard for environments that have already removed
    // the leading ESC byte before the line reaches us.
    line.startsWith("]777;notify;")

  private def stripTerminalControlSequences(text: String): String =
    val out = new StringBuilder(text.length)
    var i = 0
    while i < text.length do
      text.charAt(i) match
        case Esc                     => i = skipEsc(text, i)
        case Csi                     => i = skipCsi(text, i + 1)
        case Osc                     => i = skipStringControl(text, i + 1)
        case Dcs | Sos | Pm | Apc    => i = skipStringControl(text, i + 1)
        case c if isUnsafeControl(c) => i += 1
        case c =>
          val _ = out.append(c)
          i += 1
    out.toString

  private def skipEsc(text: String, escIndex: Int): Int =
    val nextIndex = escIndex + 1
    if nextIndex >= text.length then text.length
    else
      text.charAt(nextIndex) match
        case '['                   => skipCsi(text, nextIndex + 1)
        case ']'                   => skipStringControl(text, nextIndex + 1)
        case 'P' | 'X' | '^' | '_' => skipStringControl(text, nextIndex + 1)
        case _                     => math.min(nextIndex + 1, text.length)

  private def skipCsi(text: String, start: Int): Int =
    var i = start
    while i < text.length && !isCsiFinal(text.charAt(i)) do i += 1
    if i < text.length then i + 1 else text.length

  private def skipStringControl(text: String, start: Int): Int =
    var i = start
    var done = false
    while i < text.length && !done do
      text.charAt(i) match
        case Bel =>
          i += 1
          done = true
        case Esc if i + 1 < text.length && text.charAt(i + 1) == '\\' =>
          i += 2
          done = true
        case _ => i += 1
    i

  private def isCsiFinal(c: Char): Boolean = c >= '@' && c <= '~'

  private def isUnsafeControl(c: Char): Boolean =
    (c < ' ' && c != '\n' && c != '\t') || (c >= 0x7f.toChar && c <= Apc)

  private def appendBounded(
      buf: Vector[String],
      line: String
  ): Vector[String] =
    var result = buf :+ line
    while result.size > StderrMaxLines do result = result.tail
    while result.size > 1 && result.map(_.length).sum > StderrMaxBytes do
      result = result.tail
    result

  private def closeQuietly(resource: AutoCloseable): Unit =
    try resource.close()
    catch case NonFatal(_) => ()
