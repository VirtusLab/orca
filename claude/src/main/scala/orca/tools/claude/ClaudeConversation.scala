package orca.tools.claude

import orca.llm.{AutoApprove, BackendTag, LlmConfig, SessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ApprovalDecision, ConversationEvent, LlmResult}
import orca.backend.StreamConversation
import orca.subprocess.PipedCliProcess
import orca.tools.claude.streamjson.{
  ContentBlock,
  ControlDecision,
  ControlRequestBody,
  InboundMessage,
  OutboundMessage,
  StreamEventPayload
}

import scala.util.control.NonFatal

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
    val outputSchema: Option[String] = None,
    askUserBridge: Option[orca.tools.claude.mcp.AskUserBridge] = None,
    /** Session-scoped resources to release when this conversation ends —
      * typically the MCP server bound for this conversation's `ask_user` tool.
      * Closed from `onFinalize`, after the reader loop exits, so server
      * lifetime tracks the conversation rather than the enclosing supervised
      * scope. Without this, a long flow that opens many interactive
      * conversations would leak one Netty binding per call.
      */
    sessionResources: List[AutoCloseable] = Nil
) extends StreamConversation[BackendTag.ClaudeCode.type](
      process = process,
      backendName = "claude",
      initialPrompt = initialPrompt
    ):

  import StreamConversation.Outcome

  // The reader thread is the sole writer for all three fields below.
  // No cross-thread visibility concerns: reads happen on the same thread
  // immediately after writes, within `handle(...)` dispatch. Plain `var`s
  // suffice — atomics would be theatre.

  /** Captured from the `system.init` message so `handleResult` can fall back to
    * it when the `result` message itself doesn't carry the resolved model id.
    * Some Claude CLI versions emit it in one but not both.
    */
  private var initModel: Option[String] = None

  /** Set whenever a delta arrives in the current turn, cleared when the full
    * turn message lands. Lets `handleAssistantTurn` tell "partials already
    * streamed" from "partials disabled upstream".
    */
  private var deltasSinceTurnBoundary: Boolean = false

  /** Tool-use ids of `ask_user` calls suppressed in `handleAssistantTurn`.
    * `handleUserTurn` drops the matching `tool_result` so the user's typed
    * answer doesn't re-render as `⎿ <answer>` right after the UserQuestion
    * prompt already surfaced it.
    */
  private var suppressedToolUseIds: Set[String] = Set.empty

  // --- Conversation surface (only the bit not covered by the base) ---

  def sendUserMessage(text: String): Unit =
    writeOutbound(OutboundMessage.UserText(text))

  // canAskUser tracks whether the MCP host-bridge is wired — true when the
  // backend spun up an AskUserMcpServer for this session, false for headless
  // calls or backends that don't expose ask_user. Mid-session user input
  // doesn't flow through `sendUserMessage` (stdin is closed right after the
  // initial prompt); it flows through the MCP tool result instead.
  def canAskUser: Boolean = askUserBridge.isDefined

  // Drainer for the MCP bridge: when an ask_user tool invocation lands on
  // the MCP handler thread, the handler enqueues a (question, reply) pair
  // on the bridge; this thread reads them and surfaces them as
  // ConversationEvent.UserQuestion. The renderer prompts the user and calls
  // `respond` with the typed answer, which signals the bridge's reply
  // channel, unblocking the MCP handler so it can return the answer as the
  // tool result.
  //
  // Daemon thread mirrors the existing reader/stderr threads — the
  // conversation lifecycle is managed via process tear-down, not Ox forks.
  // Interrupted on cancel() via the process exit propagating up.
  askUserBridge.foreach: bridge =>
    val t = new Thread(
      () => askUserDrainLoop(bridge),
      "claude-conversation-ask-user"
    )
    t.setDaemon(true)
    t.start()

  private def askUserDrainLoop(
      bridge: orca.tools.claude.mcp.AskUserBridge
  ): Unit =
    try
      while !Thread.currentThread().isInterrupted do
        val q = bridge.nextQuestion()
        eventQueue.enqueue(
          ConversationEvent.UserQuestion(q.question, q.respond)
        )
    catch
      // Bridge channel closure (onFinalize → bridge.close()) propagates as
      // a ChannelClosedException from take(). Exit quietly. NonFatal so an
      // InterruptedException still propagates if something else interrupts
      // this thread.
      case NonFatal(_) => ()

  // --- Reader hook ---

  override protected def handleLine(line: String): Unit =
    handle(InboundMessage.parse(line))

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      "claude exited cleanly but never sent a result message"
    )

  /** Release session-scoped resources once the read loop has drained.
    *
    * Order matters: close the bridge **before** the MCP server. Closing the
    * bridge errors any Netty worker currently blocked on `reply.receive()` (the
    * handler returns with a tool error to the agent) and `done`s the pending
    * queue (the drainer thread unwinds). If we stopped the server first, those
    * workers would be parked when the binding tore them down, losing the chance
    * to return a clean tool result.
    *
    * Failures here are swallowed — the conversation is already winding down and
    * a close-time throw would only mask the real cause upstream.
    */
  override protected def onFinalize(): Unit =
    askUserBridge.foreach: bridge =>
      try bridge.close()
      catch case NonFatal(_) => ()
    sessionResources.foreach: r =>
      try r.close()
      catch case NonFatal(_) => ()

  // --- Per-message dispatch ---

  private def handle(msg: InboundMessage): Unit = msg match
    case InboundMessage.SystemInit(_, model) =>
      initModel = model
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
        deltasSinceTurnBoundary = true
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
    val sawDeltasThisTurn = deltasSinceTurnBoundary
    deltasSinceTurnBoundary = false
    content.foreach:
      // Suppress the agent's own ToolCall block for `ask_user` — the
      // host-side bridge emits a UserQuestion event for the same exchange
      // and rendering the tool-call line on top of it is just noise.
      // Remember the id so `handleUserTurn` can also drop the matching
      // tool_result (otherwise the user's typed answer re-renders as
      // `⎿ <answer>` after the prompt already surfaced it).
      case ContentBlock.ToolUse(id, name, _)
          if name == ClaudeBackend.AskUserToolName =>
        suppressedToolUseIds = suppressedToolUseIds + id
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
      case ContentBlock.ToolResult(toolUseId, _, _)
          if suppressedToolUseIds.contains(toolUseId) =>
        // Paired with a suppressed `ask_user` ToolUse; the user has already
        // seen their own typed answer at the prompt, so don't echo it back.
        suppressedToolUseIds = suppressedToolUseIds - toolUseId
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
      model = model.orElse(initModel)
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
      if deltasSinceTurnBoundary then "session failed (see message above)"
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
