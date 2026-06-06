package orca.tools.pi

import orca.events.Usage
import orca.llm.{BackendTag, Model, SessionId}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, LlmResult}
import orca.backend.{StreamConversation, StreamSource}
import orca.subprocess.PipedCliProcess
import orca.util.TerminalControl
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
    val trimmed = TerminalControl.stripControlSequences(line).trim
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
