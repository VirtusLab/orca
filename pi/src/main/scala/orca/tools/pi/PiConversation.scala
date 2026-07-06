package orca.tools.pi

import orca.events.Usage
import orca.agents.{BackendTag, Model, SessionId, onWire}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, AgentResult}
import orca.backend.{
  BufferedStderrDiagnostics,
  ForkedConversation,
  StreamSource
}
import orca.subprocess.PipedCliProcess
import orca.util.TerminalControl
import orca.tools.pi.rpc.{
  AgentMessage,
  InboundEvent,
  MessageDelta,
  OutboundMessage
}

import ox.Ox

import scala.util.control.NonFatal

/** Drives one `pi --mode rpc` process for a single Orca LLM call. The backend
  * sends one `prompt` command, this conversation translates Pi RPC events into
  * Orca conversation events, and `agent_end` becomes the terminal
  * [[AgentResult]].
  *
  * Pi has no native structured-output / JSON-schema flag, so `outputSchema` is
  * carried only for the framework's parsing: the schema is enforced through the
  * prompt (`DefaultAgentCall` injects the rules), not the Pi CLI.
  */
private[pi] class PiConversation(
    process: PipedCliProcess,
    clientSession: SessionId[BackendTag.Pi.type],
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    askUserEnabled: Boolean = false,
    resources: List[AutoCloseable] = Nil
)(using Ox)
    extends ForkedConversation[BackendTag.Pi.type](
      StreamSource.fromProcess(process),
      backendName = "pi",
      initialPrompt = initialPrompt,
      nativeAskUser = askUserEnabled
    )
    with BufferedStderrDiagnostics[BackendTag.Pi.type]:

  import PiConversation.*

  /** Turn state, accrued by the single reader thread — `handleLine` and the
    * handlers it drives all run there, so a plain `var` over an immutable
    * snapshot is safe and avoids cross-thread machinery; `awaitResult` reads
    * the outcome only after joining the reader, which publishes these writes.
    *
    * `textStreamedThisMessage` lets `message_end` emit the completed text as a
    * fallback only when no `text_delta` already streamed it;
    * `sawAssistantActivity` gates the single `AssistantTurnEnd` at `agent_end`
    * — set by any assistant activity (text/thinking delta OR tool call), so a
    * tool-call-only turn still terminates and an error-only `message_end`
    * (which emits no activity) doesn't produce an empty turn.
    */
  private case class TurnState(
      lastAssistantMessage: String = "",
      usage: Usage = Usage.empty,
      model: Option[String] = None,
      textStreamedThisMessage: Boolean = false,
      sawAssistantActivity: Boolean = false
  )
  private var turnState: TurnState = TurnState()

  // All stdin writes funnel through this lock: `sendPrompt` runs on the caller's
  // thread, the ask-user reply on the event consumer's, and the reader fork may
  // write an extension cancel. `writeLine` is an unsynchronised write+flush, so
  // concurrent callers would otherwise interleave JSONL frames.
  private val stdinLock = new AnyRef

  // No `start()`: the base spawns its reader / stderr forks lazily on first
  // touch of the conversation surface, after this subclass's fields (incl.
  // `stdinLock`) are initialised.

  def sendPrompt(prompt: String): Unit =
    sendLine(OutboundMessage.prompt(prompt))

  private def sendLine(line: String): Unit =
    stdinLock.synchronized(process.writeLine(line))

  private def closeStdin(): Unit =
    stdinLock.synchronized(process.closeStdin())

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  override protected def handleStderr(line: String): Unit =
    val trimmed = TerminalControl.stripControlSequences(line).trim
    if trimmed.nonEmpty && !isKnownStderrNoise(trimmed) then
      eventQueue.enqueue(ConversationEvent.Error(s"pi: $trimmed"))
      recordStderr(trimmed)

  // Drain stderr (base) then close the per-turn temp resources.
  override protected def onFinalize(): Unit =
    super.onFinalize()
    resources.foreach(closeQuietly)

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      appendContext("pi exited cleanly but never emitted agent_end")
    )

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.Response(_, command, success, error) =>
      handleResponse(command, success, error)
    case InboundEvent.MessageUpdate(delta) => handleDelta(delta)
    case InboundEvent.MessageEnd(message)  => handleMessageEnd(message)
    case InboundEvent.AgentEnd             => handleAgentEnd()
    case InboundEvent.ToolExecutionStart(toolName, rawArgs) =>
      turnState = turnState.copy(sawAssistantActivity = true)
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
        sawAssistantActivity = true,
        textStreamedThisMessage =
          turnState.textStreamedThisMessage || text.nonEmpty
      )
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
    case MessageDelta.Thinking(text) =>
      turnState = turnState.copy(sawAssistantActivity = true)
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case MessageDelta.Other(_) => ()

  private def handleMessageEnd(message: AgentMessage): Unit =
    if message.role == "assistant" then
      message.errorMessage.foreach: error =>
        if error.nonEmpty then
          eventQueue.enqueue(ConversationEvent.Error(error))
      val streamed = turnState.textStreamedThisMessage
      // The fallback below is the message_end's own assistant activity when no
      // delta streamed; an error-only message (empty text) emits nothing, so it
      // must not register activity (else agent_end would close an empty turn).
      val fallbackText = message.text.nonEmpty && !streamed
      turnState = turnState.copy(
        lastAssistantMessage = message.text,
        usage = message.usage.fold(turnState.usage)(turnState.usage + _),
        model = message.model.orElse(turnState.model),
        sawAssistantActivity = turnState.sawAssistantActivity || fallbackText,
        textStreamedThisMessage = false // reset for the next message
      )
      if fallbackText then
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(message.text))

  // A turn can span several assistant messages (each ends with `message_end`),
  // so the single turn boundary is `agent_end`, not per-message.
  private def handleAgentEnd(): Unit =
    if turnState.sawAssistantActivity then
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
    val result = AgentResult[BackendTag.Pi.type](
      wireId = clientSession.onWire,
      output = turnState.lastAssistantMessage,
      usage = turnState.usage,
      model = turnState.model.map(Model.apply)
    )
    // Closing stdin tells Pi no more commands are coming; `succeedWith` sets the
    // outcome then interrupts the source (SIGINTs the process).
    closeStdin()
    succeedWith(result)

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
              // The turn may have already reached agent_end (stdin closed) by
              // the time a human answers; a late reply is moot, so don't let the
              // write blow up the consumer thread.
              try sendLine(OutboundMessage.extensionUiValue(id, answer))
              catch case NonFatal(_) => ()
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

  private val FireAndForgetUiMethods: Set[String] = Set(
    "notify",
    "setStatus",
    "setWidget",
    "setTitle",
    "set_editor_text"
  )

  private def isKnownStderrNoise(line: String): Boolean =
    // Pi's terminal notifier can write iTerm2-style notifications to stderr as
    // OSC 777 (`ESC ] 777 ; ... BEL`). We strip well-formed controls before
    // trimming, but keep this guard for environments that have already removed
    // the leading ESC byte before the line reaches us.
    line.startsWith("]777;notify;")
