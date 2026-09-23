package orca.tools.pi

import orca.events.{TurnDebit, Usage}
import orca.agents.{
  BackendTag,
  Model,
  SessionId,
  StructuredOutputMode,
  WireSessionId
}
import orca.backend.{
  AgentResult,
  AskUserChannel,
  Conversation,
  ConversationEvent,
  ConversationSpec,
  LineDecoder,
  Settled,
  Step,
  StreamConversation,
  StreamSource
}
import orca.subprocess.PipedCliProcess
import orca.tools.pi.rpc.{
  AgentMessage,
  InboundEvent,
  MessageDelta,
  OutboundMessage
}

import ox.Ox

/** pi's stdin. Every write takes one lock: a question's answer (event consumer
  * thread) and the reader's replies and close can race, and `writeLine` is an
  * unsynchronised write+flush, so concurrent callers would interleave JSONL
  * frames.
  */
private[pi] final class PiStdin(process: PipedCliProcess):
  private val lock = new AnyRef

  def send(line: String): Unit = lock.synchronized(process.writeLine(line))

  def close(): Unit = lock.synchronized(process.closeStdin())

  /** Answer an extension UI request, best-effort. `agent_end` closes stdin
    * while the reader may still hold buffered lines, and a human may still be
    * typing, so a reply can land on a closed pipe — by then the answer no
    * longer matters. Dropping it keeps a late write from failing the consumer
    * thread, or from showing up on the reader thread as a parse error.
    *
    * The drop is logged because a broken pipe from pi dying mid-turn looks the
    * same here as a reply that arrived too late.
    */
  def reply(line: String): Unit =
    try send(line)
    catch
      case e: java.io.IOException =>
        StreamConversation.trace(
          "pi",
          "stdin",
          s"dropped extension UI reply: ${e.getMessage}"
        )

/** Decodes one `pi --mode rpc` process for a single Orca LLM call: Pi RPC
  * events → Orca conversation events, with `agent_end` as the terminal
  * [[AgentResult]].
  */
private[pi] final class PiDecoder(
    clientSession: SessionId[BackendTag.Pi.type],
    stdin: PiStdin
) extends LineDecoder[BackendTag.Pi.type, PiDecoder.State]:

  import PiConversation.*
  import PiDecoder.State

  private type Out = Step[BackendTag.Pi.type, State]

  def backendName: String = "pi"

  def terminalMessageNoun: String = "an agent_end event"

  def init: State = State("", None, None, textStreamedThisMessage = false)

  def line(state: State, line: String): Out =
    InboundEvent.parse(line) match
      case InboundEvent.Response(_, command, success, error) =>
        response(state, command, success, error)
      case InboundEvent.MessageUpdate(delta) => messageDelta(state, delta)
      case InboundEvent.MessageEnd(message)  => messageEnd(state, message)
      case InboundEvent.AgentEnd             => agentEnd(state)
      case InboundEvent.ToolExecutionStart(toolName, rawArgs) =>
        Step.continue(
          state,
          ConversationEvent.AssistantToolCall(toolName, rawArgs)
        )
      case InboundEvent.ToolExecutionEnd(toolName, ok, content) =>
        Step.continue(
          state,
          ConversationEvent.ToolResult(Some(toolName), ok, content)
        )
      case InboundEvent.ExtensionUiRequest(id, method, question) =>
        extensionUiRequest(state, id, method, question)
      case InboundEvent.Unknown(_) => Step.continue(state)

  override def isStderrNoise(line: String): Boolean = isKnownStderrNoise(line)

  /** A pi turn runs many assistant messages, each carrying its own usage, so a
    * late RPC failure would otherwise discard everything already accrued.
    */
  def failedTurnDebit(state: State): TurnDebit =
    state.usage.fold(TurnDebit.Unobserved): usage =>
      TurnDebit.Observed(usage, state.model.map(Model.apply))

  private def response(
      state: State,
      command: Option[String],
      success: Boolean,
      error: Option[String]
  ): Out =
    if success then Step.continue(state)
    else
      val message = error
        .filter(_.nonEmpty)
        .getOrElse(
          command.fold("pi RPC command failed")(c =>
            s"pi RPC command '$c' failed"
          )
        )
      Step.Settle(
        state,
        List(ConversationEvent.Error(message)),
        Settled.Failed(message, failedTurnDebit(state))
      )

  private def messageDelta(state: State, delta: MessageDelta): Out =
    delta match
      case MessageDelta.Text(text) =>
        Step.continue(
          state.copy(textStreamedThisMessage =
            state.textStreamedThisMessage || text.nonEmpty
          ),
          ConversationEvent.AssistantTextDelta(text)
        )
      case MessageDelta.Thinking(text) =>
        Step.continue(state, ConversationEvent.AssistantThinkingDelta(text))
      case MessageDelta.Other(_) => Step.continue(state)

  private def messageEnd(state: State, message: AgentMessage): Out =
    if message.role != "assistant" then Step.continue(state)
    else
      val error = message.errorMessage
        .filter(_.nonEmpty)
        .map(ConversationEvent.Error(_))
      // Fallback: surface the message_end's own text only when no delta already
      // streamed it. An error-only message (empty text) emits nothing.
      val fallbackText =
        Option.when(message.text.nonEmpty && !state.textStreamedThisMessage)(
          ConversationEvent.AssistantTextDelta(message.text)
        )
      Step.Continue(
        State(
          lastAssistantMessage = message.text,
          usage = (state.usage ++ message.usage).reduceOption(_ + _),
          model = message.model.orElse(state.model),
          textStreamedThisMessage = false
        ),
        error.toList ++ fallbackText.toList
      )

  /** A turn can span several assistant messages, so the turn boundary is
    * `agent_end`, not per-message. Stdin closes first: the settle then SIGINTs
    * the process, so no command can follow.
    */
  private def agentEnd(state: State): Out =
    stdin.close()
    Step.Settle(
      state,
      Nil,
      Settled.Succeeded(
        AgentResult[BackendTag.Pi.type](
          WireSessionId(clientSession.value),
          state.lastAssistantMessage,
          state.usage.getOrElse(Usage.empty),
          state.model.map(Model.apply)
        )
      )
    )

  private def extensionUiRequest(
      state: State,
      id: String,
      method: String,
      question: String
  ): Out =
    method match
      case "input" | "editor" =>
        Step.continue(
          state,
          ConversationEvent.UserQuestion(
            question,
            answer => stdin.reply(OutboundMessage.extensionUiValue(id, answer))
          )
        )
      // TUI decoration/status; fire-and-forget in RPC mode, so ignore them.
      case method if FireAndForgetUiMethods.contains(method) =>
        Step.continue(state)
      case other =>
        stdin.reply(OutboundMessage.extensionUiCancelled(id))
        Step.continue(
          state,
          ConversationEvent.Error(
            s"Unsupported Pi extension UI request '$other': $question"
          )
        )

private[pi] object PiDecoder:

  /** @param textStreamedThisMessage
    *   lets `message_end` emit its text as a fallback only when no `text_delta`
    *   already streamed it
    */
  final case class State(
      lastAssistantMessage: String,
      usage: Option[Usage],
      model: Option[String],
      textStreamedThisMessage: Boolean
  )

private[pi] object PiConversation:

  /** Sends `prompt`, then starts decoding `process` into the caller's turn
    * scope.
    *
    * Pi has no native structured-output flag, so `outputSchema` is carried only
    * for the framework's parsing; the schema is enforced through the prompt.
    */
  def apply(
      process: PipedCliProcess,
      clientSession: SessionId[BackendTag.Pi.type],
      prompt: Option[String] = None,
      initialPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: AskUserChannel = AskUserChannel.Unavailable
  )(using Ox): Conversation[BackendTag.Pi.type] =
    val stdin = PiStdin(process)
    prompt.foreach(p => stdin.send(OutboundMessage.prompt(p)))
    StreamConversation.start(
      StreamSource.fromProcess(process),
      ConversationSpec(
        openingPrompt = initialPrompt,
        outputSchema = outputSchema,
        structuredOutputMode = StructuredOutputMode.RawText,
        askUser = askUser,
        onUnsettledEnd = () => ()
      )
    )(_ => PiDecoder(clientSession, stdin))

  private[pi] val FireAndForgetUiMethods: Set[String] = Set(
    "notify",
    "setStatus",
    "setWidget",
    "setTitle",
    "set_editor_text"
  )

  private[pi] def isKnownStderrNoise(line: String): Boolean =
    // Pi's terminal notifier writes iTerm2 OSC 777 notifications to stderr
    // (`ESC ] 777 ; ... BEL`). Well-formed controls are stripped before
    // trimming; this guard catches lines that already lost the leading ESC.
    line.startsWith("]777;notify;")
