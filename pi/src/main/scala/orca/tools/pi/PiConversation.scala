package orca.tools.pi

import orca.events.Usage
import orca.agents.{BackendTag, SessionId}
import orca.{OrcaFlowException}
import orca.backend.ConversationEvent
import orca.backend.{StderrPipeline, ForkedConversation, StreamSource}
import orca.subprocess.PipedCliProcess
import orca.tools.pi.rpc.{
  AgentMessage,
  InboundEvent,
  MessageDelta,
  OutboundMessage
}

import scala.util.control.NonFatal

/** Drives one `pi --mode rpc` process for a single Orca LLM call: translates Pi
  * RPC events into Orca conversation events, with `agent_end` as the terminal
  * [[AgentResult]].
  *
  * Pi has no native structured-output flag, so `outputSchema` is carried only
  * for the framework's parsing; the schema is enforced through the prompt, not
  * the Pi CLI.
  */
private[pi] class PiConversation(
    process: PipedCliProcess,
    clientSession: SessionId[BackendTag.Pi.type],
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    askUserEnabled: Boolean = false,
    resources: List[AutoCloseable] = Nil
) extends ForkedConversation[BackendTag.Pi.type](
      StreamSource.fromProcess(process),
      backendName = "pi",
      initialPrompt = initialPrompt,
      nativeAskUser = askUserEnabled
    )
    with StderrPipeline[BackendTag.Pi.type]:

  import PiConversation.*

  /** Turn state, accrued only by the single reader thread, so a plain `var` is
    * safe; `awaitResult` reads the outcome after joining the reader, which
    * publishes these writes.
    *
    * `textStreamedThisMessage` lets `message_end` emit its text as a fallback
    * only when no `text_delta` already streamed it.
    */
  private case class TurnState(
      lastAssistantMessage: String = "",
      usage: Usage = Usage.empty,
      model: Option[String] = None,
      textStreamedThisMessage: Boolean = false
  )
  private var turnState: TurnState = TurnState()

  // All stdin writes funnel through this lock: `sendPrompt` (caller thread), the
  // ask-user reply (event consumer), and the reader fork's extension cancel can
  // race. `writeLine` is an unsynchronised write+flush, so concurrent callers
  // would otherwise interleave JSONL frames.
  private val stdinLock = new AnyRef

  // The base spawns its reader/stderr forks lazily on first touch, after this
  // subclass's fields (incl. `stdinLock`) are initialised.

  def sendPrompt(prompt: String): Unit =
    sendLine(OutboundMessage.prompt(prompt))

  private def sendLine(line: String): Unit =
    stdinLock.synchronized(process.writeLine(line))

  private def closeStdin(): Unit =
    stdinLock.synchronized(process.closeStdin())

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  override protected def isStderrNoise(line: String): Boolean =
    isKnownStderrNoise(line)

  // Drain stderr (base) then close the per-turn temp resources.
  override protected def onFinalize(): Unit =
    super.onFinalize()
    resources.foreach(closeQuietly)

  override protected def terminalMessageNoun: String = "an agent_end event"

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.Response(_, command, success, error) =>
      handleResponse(command, success, error)
    case InboundEvent.MessageUpdate(delta) => handleDelta(delta)
    case InboundEvent.MessageEnd(message)  => handleMessageEnd(message)
    case InboundEvent.AgentEnd             => handleAgentEnd()
    case InboundEvent.ToolExecutionStart(toolName, rawArgs) =>
      eventQueue.enqueue(ConversationEvent.AssistantToolCall(toolName, rawArgs))
    case InboundEvent.ToolExecutionEnd(toolName, ok, content) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(Some(toolName), ok, content)
      )
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
      failWith(new OrcaFlowException(message))

  private def handleDelta(delta: MessageDelta): Unit = delta match
    case MessageDelta.Text(text) =>
      turnState = turnState.copy(
        textStreamedThisMessage =
          turnState.textStreamedThisMessage || text.nonEmpty
      )
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
      // Fallback: surface the message_end's own text only when no delta already
      // streamed it. An error-only message (empty text) emits nothing.
      val fallbackText = message.text.nonEmpty && !streamed
      turnState = turnState.copy(
        lastAssistantMessage = message.text,
        usage = message.usage.fold(turnState.usage)(turnState.usage + _),
        model = message.model.orElse(turnState.model),
        textStreamedThisMessage = false // reset for the next message
      )
      if fallbackText then
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(message.text))

  // A turn can span several assistant messages, so the turn boundary is
  // `agent_end`, not per-message. It's terminal, so the base funnel auto-closes
  // the open turn on `succeedWith` — no explicit `AssistantTurnEnd` here.
  private def handleAgentEnd(): Unit =
    // Close stdin first: `settleSuccess` then interrupts the source (SIGINTs the
    // process), so no more commands can be sent afterward.
    closeStdin()
    settleSuccess(
      wireId = clientSession.value,
      output = turnState.lastAssistantMessage,
      usage = turnState.usage,
      modelId = turnState.model
    )

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
              // Stdin may already be closed (agent_end reached) by the time a
              // human answers; a late reply is moot, so don't let the write
              // blow up the consumer thread.
              try sendLine(OutboundMessage.extensionUiValue(id, answer))
              catch case NonFatal(_) => ()
          )
        )
      case method if FireAndForgetUiMethods.contains(method) =>
        // TUI decoration/status; fire-and-forget in RPC mode, so ignore them.
        ()
      case other =>
        eventQueue.enqueue(
          ConversationEvent.Error(
            s"Unsupported Pi extension UI request '$other': $question"
          )
        )
        sendLine(OutboundMessage.extensionUiCancelled(id))

private[pi] object PiConversation:

  private val FireAndForgetUiMethods: Set[String] = Set(
    "notify",
    "setStatus",
    "setWidget",
    "setTitle",
    "set_editor_text"
  )

  private def isKnownStderrNoise(line: String): Boolean =
    // Pi's terminal notifier writes iTerm2 OSC 777 notifications to stderr
    // (`ESC ] 777 ; ... BEL`). Well-formed controls are stripped before
    // trimming; this guard catches lines that already lost the leading ESC.
    line.startsWith("]777;notify;")
