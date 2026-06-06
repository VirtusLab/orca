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
  *
  * Pi has no native structured-output / JSON-schema flag, so `outputSchema` is
  * carried only for the framework's parsing: the schema is enforced through the
  * prompt (`DefaultLlmCall` injects the rules), not the Pi CLI.
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

  /** Turn state, accrued by the single reader thread — `handleLine` and the
    * handlers it drives all run there, so a plain `var` over an immutable
    * snapshot is safe and avoids cross-thread machinery; `awaitResult` reads the
    * outcome only after joining the reader, which publishes these writes.
    *
    * `textStreamedThisMessage` lets `message_end` emit the completed text as a
    * fallback only when no `text_delta` already streamed it; `sawAssistantMessage`
    * gates the single `AssistantTurnEnd` at `agent_end`.
    */
  private case class TurnState(
      lastAssistantMessage: String = "",
      usage: Usage = Usage.empty,
      model: Option[String] = None,
      textStreamedThisMessage: Boolean = false,
      sawAssistantMessage: Boolean = false
  )
  private var turnState: TurnState = TurnState()

  /** Atomic, unlike [[turnState]]: written by the *stderr* drain thread
    * ([[handleStderr]]) and read by the reader thread at finalize.
    */
  private val stderrBuffer = new AtomicReference[Vector[String]](Vector.empty)

  // All stdin writes funnel through this lock: `sendPrompt` runs on the caller's
  // thread, the ask-user reply on the event consumer's, and the reader thread
  // may write an extension cancel. `writeLine` is an unsynchronised write+flush,
  // so concurrent callers would otherwise interleave JSONL frames. Declared
  // before `start()` so the reader thread never observes a null lock.
  private val stdinLock = new AnyRef

  start()

  def sendPrompt(prompt: String): Unit =
    sendLine(OutboundMessage.prompt(prompt))

  private def sendLine(line: String): Unit =
    stdinLock.synchronized(process.writeLine(line))

  private def closeStdin(): Unit =
    stdinLock.synchronized(process.closeStdin())

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
      if text.nonEmpty then
        turnState = turnState.copy(textStreamedThisMessage = true)
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
    case MessageDelta.Thinking(text) =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case MessageDelta.Other(_) => ()

  private def handleMessageEnd(message: AgentMessage): Unit =
    if message.role == "assistant" then
      message.errorMessage.foreach: error =>
        if error.nonEmpty then
          eventQueue.enqueue(ConversationEvent.Error(error))
      val streamed = turnState.textStreamedThisMessage
      turnState = turnState.copy(
        lastAssistantMessage = message.text,
        usage = message.usage.fold(turnState.usage)(turnState.usage + _),
        model = message.model.orElse(turnState.model),
        sawAssistantMessage = true,
        textStreamedThisMessage = false // reset for the next message
      )
      if message.text.nonEmpty && !streamed then
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(message.text))

  // A turn can span several assistant messages (each ends with `message_end`),
  // so the single turn boundary is `agent_end`, not per-message.
  private def handleAgentEnd(): Unit =
    if turnState.sawAssistantMessage then
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
    val result = LlmResult(
      sessionId = clientSession,
      output = turnState.lastAssistantMessage,
      usage = turnState.usage,
      model = turnState.model.map(Model.apply)
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))
    closeStdin()
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
              sendLine(OutboundMessage.extensionUiValue(id, answer))
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
        sendLine(OutboundMessage.extensionUiCancelled(id))

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
