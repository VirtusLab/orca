package orca.tools.codex

import orca.llm.{BackendTag, Model, SessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, LlmResult}
import orca.backend.{
  BufferedStderrDiagnostics,
  StreamConversation,
  StreamSource
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

import java.util.concurrent.atomic.AtomicReference

/** Drives a `codex exec --json` session to completion. Boilerplate lives in
  * [[StreamConversation]]; this class supplies the codex-specific protocol
  * translation: JSONL → [[InboundEvent]] → `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate, driven by codex's JSONL protocol
  * — see ADR 0007):
  *   - codex emits whole `agent_message` items, not per-token deltas; each
  *     becomes one `AssistantTextDelta` + one `AssistantTurnEnd`.
  *   - codex doesn't negotiate tool approvals over the wire; `autoApprove` is
  *     pre-baked into spawn args. `ApproveTool` is never emitted here.
  *   - `codex exec` is one-shot; multi-turn happens via `codex exec resume` on
  *     a fresh spawn, so `sendUserMessage` is a no-op.
  *   - **`LlmResult.output` is synthesised**: codex has no terminal message
  *     carrying the structured payload, so [[lastAgentMessage]] snapshots each
  *     agent message and the result builder reads the last one at
  *     `turn.completed`. The prompt template makes the last message JSON.
  */
private[codex] class CodexConversation(
    process: PipedCliProcess,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    override val askUser: Option[AskUserSession] = None
) extends StreamConversation[BackendTag.Codex.type](
      source = StreamSource.fromProcess(process),
      backendName = "codex",
      initialPrompt = initialPrompt
    )
    with BufferedStderrDiagnostics[BackendTag.Codex.type]:

  import CodexConversation.*
  import StreamConversation.Outcome

  private val sessionIdRef = new AtomicReference[String]("")
  private val modelRef = new AtomicReference[Option[String]](None)

  /** The most recent agent_message text the model produced. See the class
    * scaladoc for why we synthesise rather than receive.
    */
  private val lastAgentMessage = new AtomicReference[String]("")

  /** MCP item ids whose `AssistantToolCall` echo we drop — the host-side bridge
    * has already surfaced the corresponding `UserQuestion` event, so rendering
    * the tool call on top would be noise. The matching `item.completed` is also
    * suppressed: the user's typed answer would otherwise re-render as `⎿
    * <answer>` after the prompt surfaced it.
    *
    * Single-threaded reader (the JSONL reader thread is the sole writer), so a
    * plain `var` Set is enough.
    */
  private var suppressedMcpItemIds: Set[String] = Set.empty

  // Subclass fields above are assigned now; safe to spin up the reader +
  // stderr workers. See [[StreamConversation.start]] — the base also
  // spawns the ask_user drainer if one was wired.
  start()

  // --- Conversation surface ---

  /** Codex exec consumes its prompt argv-side and ignores stdin thereafter;
    * injecting more user turns mid-session isn't supported. The contract still
    * requires a callable method — this is a no-op.
    */
  def sendUserMessage(text: String): Unit = ()

  // `canAskUser` is owned by the base — true when this conversation was
  // constructed with `askUser = Some(...)`. Codex exec has no in-session
  // stdin channel (ADR 0007), but the agent CAN reach the user via the
  // ask_user MCP tool when the bridge is wired.

  // --- Reader hooks ---

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  /** codex prints known-benign noise on every exec invocation:
    *
    *   - `Reading additional input from stdin…` whenever stdin is piped (we
    *     always pipe stdin, even though we immediately close it).
    *   - `ERROR codex_core::session: failed to record rollout items: thread
    *     <id> not found` during shutdown — fires inside codex's cleanup after
    *     the rollout writer is already torn down. The rollout file is still
    *     written correctly to `~/.codex/sessions/`; the message is harmless.
    *
    * Filter both, plus empty lines. Anything else passes through with the
    * default backend-prefixed Error event AND is recorded in the bounded stderr
    * buffer (see [[BufferedStderrDiagnostics]]) so the failure exception can
    * include them.
    */
  override protected def handleStderr(line: String): Unit =
    val trimmed = line.trim
    if trimmed.nonEmpty && !CodexConversation.isKnownStderrNoise(trimmed) then
      eventQueue.enqueue(ConversationEvent.Error(s"codex: $trimmed"))
      recordStderr(trimmed)

  override protected def cleanExitWithoutResult(): Throwable =
    // Defer the framing to the base so the diagnosticContext below gets
    // folded in with the same shape as the non-zero-exit branch.
    new OrcaFlowException(
      appendContext(
        "codex exited cleanly but never sent a turn.completed event"
      )
    )

  // --- Per-event dispatch ---

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.ThreadStarted(threadId, model) =>
      sessionIdRef.set(threadId)
      modelRef.set(model)
    case InboundEvent.TurnStarted          => ()
    case InboundEvent.TurnCompleted(usage) => handleTurnCompleted(usage)
    case InboundEvent.ItemStarted(item)    => handleItemStarted(item)
    case InboundEvent.ItemCompleted(item)  => handleItemCompleted(item)
    case InboundEvent.Unknown(_)           =>
      // Forward-compat: codex may add new top-level event types; drop
      // them silently rather than rendering ✖.
      ()

  private def handleItemStarted(item: Item): Unit = item match
    case Item.CommandExecution(_, command, _, _, _) =>
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(
          toolName = "bash",
          rawInput = writeToString(BashInput(command))
        )
      )
    case Item.FileChange(_, changes, _) =>
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(
          toolName = "file_change",
          rawInput = writeToString(FileChangeInput(changes.map(toWire)))
        )
      )
    case Item.McpToolCall(id, server, tool, _, _, _)
        if server == AskUserMcpServer.ServerName &&
          tool == AskUserMcpServer.ToolSlug =>
      // ask_user is surfaced through the host-side bridge as a
      // UserQuestion event; the matching item.completed echo is dropped
      // too — the user has already seen their typed answer at the prompt.
      suppressedMcpItemIds = suppressedMcpItemIds + id
    case Item.McpToolCall(_, server, tool, args, _, _) =>
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(
          toolName = mcpToolName(server, tool),
          rawInput = args
        )
      )
    case _ =>
      // agent_message / reasoning announce themselves at completion;
      // Other items pass through without a started event. Nothing to do.
      ()

  private def handleItemCompleted(item: Item): Unit = item match
    case Item.AgentMessage(_, text) =>
      lastAgentMessage.set(text)
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
    case Item.Reasoning(_, text) if text.nonEmpty =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case Item.Reasoning(_, _) => ()
    case Item.CommandExecution(_, _, output, exitCode, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = "bash",
          ok = exitCode.contains(0) && status == "completed",
          content = output
        )
      )
    case Item.FileChange(_, changes, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = "file_change",
          ok = status == "completed",
          content = changes.map(c => s"${c.kind} ${c.path}").mkString("\n")
        )
      )
    case Item.McpToolCall(id, _, _, _, _, _)
        if suppressedMcpItemIds.contains(id) =>
      // Matched a suppressed ask_user call started above; drop the
      // mirrored completion and clear the id.
      suppressedMcpItemIds = suppressedMcpItemIds - id
    case Item.McpToolCall(_, server, tool, _, result, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = mcpToolName(server, tool),
          ok = status == "completed",
          content = result.getOrElse("")
        )
      )
    case Item.Other(_, _) =>
      ()

  /** Compose the user-facing tool name from codex's `(server, tool)` pair.
    * Codex namespaces MCP tools by server, so a dotted form reads naturally in
    * the renderer log and stays distinct from the bare `bash` / `file_change`
    * names used by codex's built-in items.
    */
  private def mcpToolName(server: String, tool: String): String =
    s"$server.$tool"

  private def handleTurnCompleted(usage: Usage): Unit =
    val result = LlmResult(
      sessionId = SessionId[BackendTag.Codex.type](sessionIdRef.get()),
      output = lastAgentMessage.get(),
      usage = usage,
      model = modelRef.get().map(Model.apply)
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))

  private def toWire(c: FileChangeDetail): FileChangeWire =
    FileChangeWire(c.path, c.kind)

private[codex] object CodexConversation:

  /** Stderr lines codex emits unconditionally that carry no diagnostic value —
    * filtered before they reach the event queue. See
    * [[CodexConversation.handleStderr]] for what each line means.
    */
  private[codex] def isKnownStderrNoise(line: String): Boolean =
    line.startsWith("Reading additional input from stdin") ||
      line.contains(
        "codex_core::session: failed to record rollout items"
      )

  /** Synthetic JSON the driver hands the renderer for `bash` tool calls —
    * codex's `command_execution` items don't natively carry a JSON-shaped
    * input, so we wrap the command string in a one-key object the renderer can
    * introspect.
    */
  private case class BashInput(command: String) derives ConfiguredJsonValueCodec

  private case class FileChangeWire(path: String, kind: String)
      derives ConfiguredJsonValueCodec

  private case class FileChangeInput(changes: List[FileChangeWire])
      derives ConfiguredJsonValueCodec
