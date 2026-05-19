package orca.tools.claude

import orca.{
  AutoApprove,
  BackendTag,
  LlmConfig,
  OrcaFlowException,
  SessionId,
  Usage
}
import orca.backend.{ApprovalDecision, ConversationEvent, LlmResult}
import orca.subprocess.{PipedCliProcess, StreamConversation}
import orca.tools.claude.streamjson.{
  ContentBlock,
  ControlDecision,
  ControlRequestBody,
  InboundMessage,
  OutboundMessage,
  StreamEventPayload
}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** Drives a stream-json conversation with claude to completion.
  *
  * Boilerplate (reader thread, event queue, outcome lifecycle, stderr drain)
  * lives in [[StreamConversation]]; this class supplies the claude-specific
  * protocol translation: NDJSON → [[InboundMessage]] → `ConversationEvent`s,
  * plus auto-approve policy for tools listed in `config.autoApprove`. Outbound
  * writes (user turns, tool-approval responses) happen on the channel's thread
  * via `writeOutbound`.
  */
private[claude] class ClaudeConversation(
    process: PipedCliProcess,
    config: LlmConfig,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None
) extends StreamConversation[BackendTag.ClaudeCode.type](
      process = process,
      backendName = "claude",
      initialPrompt = initialPrompt
    ):

  import StreamConversation.Outcome

  private val sessionIdRef = new AtomicReference[String]("")

  /** Captured from the `system.init` message so `handleResult` can fall back to
    * it when the `result` message itself doesn't carry the resolved model id.
    * Some Claude CLI versions emit it in one but not both.
    */
  private val initModelRef = new AtomicReference[Option[String]](None)

  /** Set whenever a delta arrives in the current turn, cleared when the full
    * turn message lands. Lets `handleAssistantTurn` tell "partials already
    * streamed" from "partials disabled upstream".
    */
  private val deltasSinceTurnBoundary = new AtomicBoolean(false)

  // --- Conversation surface (only the bit not covered by the base) ---

  def sendUserMessage(text: String): Unit =
    writeOutbound(OutboundMessage.UserText(text))

  // --- Reader hook ---

  override protected def handleLine(line: String): Unit =
    handle(InboundMessage.parse(line))

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      "claude exited cleanly but never sent a result message"
    )

  // --- Per-message dispatch ---

  private def handle(msg: InboundMessage): Unit = msg match
    case InboundMessage.SystemInit(sid, model) =>
      sessionIdRef.set(sid)
      initModelRef.set(model)
    case InboundMessage.AssistantTurn(content) => handleAssistantTurn(content)
    case InboundMessage.UserTurn(content)      => handleUserTurn(content)
    case InboundMessage.Result(
          _,
          sid,
          output,
          structured,
          usage,
          isError,
          model
        ) =>
      if isError then handleResultError(output)
      else handleResult(sid, output, structured, usage, model)
    case InboundMessage.ControlRequest(reqId, body) =>
      handleControlRequest(reqId, body)
    case InboundMessage.StreamEvent(payload) =>
      translateStreamEvent(payload).foreach { evt =>
        deltasSinceTurnBoundary.set(true)
        eventQueue.enqueue(evt)
      }
    case InboundMessage.Unknown(_) =>
      // Unknown top-level message types are protocol drift (new
      // message kinds in newer CLI versions, etc.) — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      ()

  /** Full assistant turn arrives after partials have streamed. Tool-use blocks
    * only reach us here (deltas don't reconstruct them), so we emit those. Text
    * and thinking are normally already streamed as deltas, but if partials were
    * disabled upstream we'd silently drop them — fall back to emitting the
    * whole block as a single delta when no deltas preceded this turn.
    */
  private def handleAssistantTurn(content: List[ContentBlock]): Unit =
    val sawDeltasThisTurn = deltasSinceTurnBoundary.getAndSet(false)
    content.foreach:
      case ContentBlock.ToolUse(_, name, rawInput) =>
        eventQueue.enqueue(ConversationEvent.AssistantToolCall(name, rawInput))
      case ContentBlock.Text(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      case ContentBlock.Thinking(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
      case _ => ()
    eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)

  /** User turns arriving from the subprocess echo our own input, except they
    * also carry `tool_result` blocks the SDK injected after running a tool —
    * surface those so the channel can render the outcome.
    */
  private def handleUserTurn(content: List[ContentBlock]): Unit =
    content.foreach:
      case ContentBlock.ToolResult(_, body, isError) =>
        eventQueue.enqueue(
          ConversationEvent.ToolResult(
            toolName = "",
            ok = !isError,
            content = body
          )
        )
      case _ => ()

  private def handleResult(
      sid: String,
      output: Option[String],
      structured: Option[String],
      usage: Usage,
      model: Option[String]
  ): Unit =
    val result = LlmResult(
      sessionId = SessionId[BackendTag.ClaudeCode.type](sid),
      output = structured.orElse(output).getOrElse(""),
      usage = usage,
      // Fall back to the model claude announced in system.init when the
      // result message omits it.
      model = model.orElse(initModelRef.get())
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))

  /** Claude sets `is_error: true` for out-of-band failures (API errors, rate
    * limits, auth problems) that happen at the CLI boundary rather than inside
    * a turn. Treat these as session-ending failures rather than feeding the
    * error body into the downstream response parser, which might otherwise
    * accept a `{"type":"error",...}` payload as a structurally valid agent
    * output.
    *
    * If text deltas have already streamed in this turn, the user has already
    * seen the body — emit a short marker rather than repeating the full text.
    * The `Outcome.Failed` always carries the full message for `awaitResult` to
    * surface.
    */
  private def handleResultError(output: Option[String]): Unit =
    val message =
      output.filter(_.nonEmpty).getOrElse("claude reported is_error")
    val displayed =
      if deltasSinceTurnBoundary.get() then "session failed (see message above)"
      else message
    eventQueue.enqueue(ConversationEvent.Error(displayed))
    val _ = outcomeRef.compareAndSet(
      None,
      Some(
        Outcome.Failed(
          new OrcaFlowException(s"claude session failed: $message")
        )
      )
    )

  private def handleControlRequest(
      requestId: String,
      body: ControlRequestBody
  ): Unit = body match
    case ControlRequestBody.CanUseTool(name, rawInput) =>
      if autoApproves(name) then respond(requestId, ApprovalDecision.Allow())
      else
        eventQueue.enqueue(
          ConversationEvent.ApproveTool(
            toolName = name,
            rawInput = rawInput,
            respond = decision => respond(requestId, decision)
          )
        )
    case ControlRequestBody.Unknown(subtype) =>
      eventQueue.enqueue(
        ConversationEvent.Error(s"Unknown control_request subtype: $subtype")
      )

  private def autoApproves(toolName: String): Boolean = config.autoApprove match
    case AutoApprove.All         => true
    case AutoApprove.Only(tools) => tools.contains(toolName)

  private def translateStreamEvent(
      payload: StreamEventPayload
  ): Option[ConversationEvent] = payload match
    case StreamEventPayload.TextDelta(_, text) =>
      Some(ConversationEvent.AssistantTextDelta(text))
    case StreamEventPayload.ThinkingDelta(_, text) =>
      Some(ConversationEvent.AssistantThinkingDelta(text))
    case _ =>
      None // block start/stop, input-json deltas, unhandled — driver ignores

  private def respond(requestId: String, decision: ApprovalDecision): Unit =
    val controlDecision = decision match
      case ApprovalDecision.Allow(update) => ControlDecision.Allow(update)
      case ApprovalDecision.Deny(reason)  => ControlDecision.Deny(reason)
    writeOutbound(OutboundMessage.ControlResponse(requestId, controlDecision))

  private def writeOutbound(msg: OutboundMessage): Unit =
    process.writeLine(OutboundMessage.toJson(msg))
