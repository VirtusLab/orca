package orca.tools.codex

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, AgentResult}
import orca.backend.{
  StderrPipeline,
  ForkedConversation,
  StreamSource,
  SubprocessSpawn
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Drives a `codex exec --json` session to completion. Boilerplate lives in
  * [[orca.backend.ForkedConversation]]; this class supplies the codex-specific
  * protocol translation: JSONL → [[InboundEvent]] → `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate, driven by codex's JSONL protocol
  * — see ADR 0007):
  *   - codex emits whole `agent_message` items, not per-token deltas; each
  *     becomes one `AssistantTextDelta` + one `AssistantTurnEnd`.
  *   - codex doesn't negotiate tool approvals over the wire; `autoApprove` is
  *     pre-baked into spawn args. `ApproveTool` is never emitted here.
  *   - `codex exec` is one-shot; multi-turn happens via `codex exec resume` on
  *     a fresh spawn, so `sendUserMessage` is a no-op.
  *   - **`AgentResult.output` is synthesised**: codex has no terminal message
  *     carrying the structured payload, so [[lastAgentMessage]] snapshots each
  *     agent message and the result builder reads the last one at
  *     `turn.completed`. The prompt template makes the last message JSON.
  */
private[codex] class CodexConversation(
    process: PipedCliProcess,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    override val askUser: Option[AskUserSession] = None,
    /** The temp file backing `--output-schema` (if any), owned by this
      * conversation so it's removed exactly once the turn finalizes — see
      * [[orca.backend.SubprocessSpawn.deleteFileResource]] and [[onFinalize]].
      */
    schemaFile: Option[os.Path] = None,
    /** The model this agent was configured with, if any. Used as the cost-
      * attribution fallback when codex's `thread.started` omits the resolved
      * model id (some `codex exec` invocations, notably `resume`, do) — without
      * it those turns' tokens land under `(unknown)` and go unpriced, so the
      * estimated total disagrees with the by-agent breakdown. Mirrors claude's
      * `system.init` → `result` model fallback ([[ClaudeConversation]]).
      */
    configuredModel: Option[Model] = None
) extends ForkedConversation[BackendTag.Codex.type](
      source = StreamSource.fromProcess(process),
      backendName = "codex",
      initialPrompt = initialPrompt
    )
    with StderrPipeline[BackendTag.Codex.type]:

  import CodexConversation.*

  // Reader-thread-confined: `sessionId`, `model` and `lastAgentMessage` below
  // are written only from `handle` (called from `handleLine`, on the reader
  // fork) and read only from `handleTurnCompleted` on that same fork.
  // `awaitResult`'s `readerFork.join()` publishes the final values to the
  // caller.
  private var sessionId: String = ""
  private var model: Option[String] = None

  /** The most recent agent_message text the model produced (reader-thread-
    * confined; see the group comment above). See the class scaladoc for why we
    * synthesise rather than receive.
    */
  private var lastAgentMessage: String = ""

  /** MCP item ids whose `AssistantToolCall` echo we drop — the host-side bridge
    * has already surfaced the corresponding `UserQuestion` event, so rendering
    * the tool call on top would be noise. The matching `item.completed` is also
    * suppressed: the user's typed answer would otherwise re-render as `⎿
    * <answer>` after the prompt surfaced it. See
    * [[orca.backend.AskUserEchoes]].
    */
  private val askUserEchoes = new orca.backend.AskUserEchoes

  // No `start()`: the base spawns its reader / stderr / ask-user forks lazily
  // on first touch of the conversation surface, after this subclass's fields
  // are initialised.

  // --- Conversation surface ---

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
    * Filter both; anything else passes through [[StderrPipeline]]'s hoisted
    * `handleStderr` (strip → trim → filter → Error event → recorded
    * diagnostic).
    */
  override protected def isStderrNoise(line: String): Boolean =
    CodexConversation.isKnownStderrNoise(line)

  /** Best-effort delete the `--output-schema` temp file (if any), then defer to
    * [[StderrPipeline.onFinalize]] to join the stderr drain. Runs exactly once
    * (reader happy-path OR `cancel()` — see `ForkedConversation.runFinalize`),
    * so the file never outlives the turn that wrote it. The delete runs in a
    * `finally` so a throw from it (e.g. the file already gone) can't skip
    * `super.onFinalize()` — and with it, the stderr-drain join it depends on.
    */
  override protected def onFinalize(): Unit =
    try schemaFile.foreach(p => SubprocessSpawn.deleteFileResource(p).close())
    finally super.onFinalize()

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
      sessionId = threadId
      this.model = model
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
      askUserEchoes.suppress(id)
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
      lastAgentMessage = text
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
    case Item.Reasoning(_, text) if text.nonEmpty =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case Item.Reasoning(_, _) => ()
    case Item.CommandExecution(_, _, output, exitCode, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = Some("bash"),
          ok = exitCode.contains(0) && status == "completed",
          content = output
        )
      )
    case Item.FileChange(_, changes, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = Some("file_change"),
          ok = status == "completed",
          content = changes.map(c => s"${c.kind} ${c.path}").mkString("\n")
        )
      )
    case Item.McpToolCall(id, _, _, _, _, _) if askUserEchoes.consume(id) =>
      // Matched a suppressed ask_user call started above; drop the mirrored
      // completion. `consume` clears the id as it matches.
      ()
    case Item.McpToolCall(_, server, tool, _, result, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = Some(mcpToolName(server, tool)),
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
    val result = AgentResult(
      wireId = WireSessionId[BackendTag.Codex.type](sessionId),
      output = lastAgentMessage,
      usage = usage,
      // Fall back to the configured model when the wire omitted it (e.g.
      // `thread.started` on a resume), so the turn's tokens are priced rather
      // than attributed to `(unknown)`.
      model = model.map(Model.apply).orElse(configuredModel)
    )
    succeedWith(result)

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
