package orca.tools.codex

import orca.AgentTurnFailed
import orca.agents.{BackendTag, Model}
import orca.events.{Usage}
import orca.backend.ConversationEvent
import orca.backend.{
  StderrPipeline,
  ForkedConversation,
  StreamSource,
  SubprocessSpawn
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item, ItemStatus}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Drives a `codex exec --json` session to completion. Boilerplate lives in
  * [[orca.backend.ForkedConversation]]; this class supplies the codex-specific
  * protocol translation: JSONL → [[InboundEvent]] → `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate, driven by codex's JSONL protocol
  * — see ADR 0007):
  *   - codex emits whole `agent_message` items, not per-token deltas; each
  *     becomes one `AssistantTextDelta`. Non-structured calls close a turn
  *     (`AssistantTurnEnd`) after every item; structured calls instead coalesce
  *     every `agent_message` into a single turn closed at `turn.completed` —
  *     see [[handleItemCompleted]].
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
    /** Cost-attribution fallback when codex's `thread.started` omits the
      * resolved model id (notably on `resume`); without it those turns' tokens
      * land under `(unknown)` and go unpriced.
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

  /** The most recent agent_message text (reader-thread-confined). See the class
    * scaladoc for why we synthesise the result rather than receive it.
    */
  private var lastAgentMessage: String = ""

  /** The last protocol-level error/turn-failure message seen (reader-thread-
    * confined), folded into [[diagnosticContext]] so a bare process exit after
    * one of these events still carries codex's own explanation rather than just
    * an exit code — see [[handleTurnFailed]].
    */
  private var lastProtocolError: Option[String] = None

  /** MCP item ids whose `AssistantToolCall` echo we drop — the host-side bridge
    * has already surfaced the corresponding `UserQuestion`, so rendering the
    * tool call (and its `item.completed` answer echo) on top would be noise.
    * See [[orca.backend.AskUserEchoes]].
    */
  private val askUserEchoes = new orca.backend.AskUserEchoes

  // No `start()` — see `ForkedConversation.ensureStarted`'s lazy fork spawn.

  // --- Reader hooks ---

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  /** codex prints known-benign noise on every exec invocation:
    *
    *   - `Reading additional input from stdin…` whenever stdin is piped (we
    *     always pipe, even though we immediately close it).
    *   - `ERROR codex_core::session: failed to record rollout items: thread
    *     <id> not found` during shutdown, after the rollout writer is torn
    *     down. The rollout file is still written correctly; the message is
    *     harmless.
    *
    * Filter both; anything else passes through [[StderrPipeline]]'s
    * `handleStderr`.
    */
  override protected def isStderrNoise(line: String): Boolean =
    CodexConversation.isKnownStderrNoise(line)

  /** Best-effort delete the `--output-schema` temp file (if any), then defer to
    * [[StderrPipeline.onFinalize]] to join the stderr drain. The delete runs in
    * a `finally` so a throw from it can't skip `super.onFinalize()` and the
    * stderr-drain join it depends on.
    */
  override protected def onFinalize(): Unit =
    try schemaFile.foreach(p => SubprocessSpawn.deleteFileResource(p).close())
    finally super.onFinalize()

  override protected def terminalMessageNoun: String =
    "a turn.completed event"

  /** Folds [[lastProtocolError]] (a codex `error`/`turn.failed` message) ahead
    * of [[StderrPipeline]]'s stderr-derived context, so any exit path —
    * including a bare process exit with no `turn.failed` event — carries
    * codex's own explanation instead of just an exit code.
    */
  override protected def diagnosticContext: Option[String] =
    val protocolCtx = lastProtocolError.map(m => s"codex error:\n    $m")
    (protocolCtx, super.diagnosticContext) match
      case (Some(p), Some(s)) => Some(s"$p\n    $s")
      case (Some(p), None)    => Some(p)
      case (None, other)      => other

  // --- Per-event dispatch ---

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.ThreadStarted(threadId, model) =>
      sessionId = threadId
      this.model = model
    case InboundEvent.TurnStarted          => ()
    case InboundEvent.TurnCompleted(usage) => handleTurnCompleted(usage)
    case InboundEvent.ItemStarted(item)    => handleItemStarted(item)
    case InboundEvent.ItemCompleted(item)  => handleItemCompleted(item)
    case InboundEvent.Error(message)       => handleError(message)
    case InboundEvent.TurnFailed(message)  => handleTurnFailed(message)
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
      // Structured calls: codex sometimes emits an early "commentary"
      // agent_message — often a verbatim draft of the eventual answer —
      // before finishing its tool calls, then a genuine final one; the wire
      // item shape carries no phase/channel field distinguishing the two
      // (ADR 0007). Leaving the turn open here coalesces every agent_message
      // of the call into ONE ConversationEvent-level turn, closed exactly
      // once by ForkedConversation's turn.completed safety net
      // (`succeedWith`'s `closeOpenTurn`). Without this, the withholding
      // buffer's one-real-turn-per-payload assumption sees the early draft as
      // a distinct, already-finished turn and echoes it as `AssistantMessage`
      // prose — the JSON payload leaking as `●` prose right before the
      // structured-result summary. Non-structured calls keep closing per item
      // so live multi-message narration (e.g. "I'll edit X." … "Updated X.")
      // still streams progressively. Deliberate tradeoff: the single coalesced
      // turn is always the buffer's last, so a structured codex call shows NO
      // intermediate agent prose at all — the wire can't distinguish genuine
      // narration from payload drafts, and suppressing both is the only way to
      // guarantee the payload never leaks.
      if outputSchema.isEmpty then
        eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
    case Item.Reasoning(_, text) if text.nonEmpty =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case Item.Reasoning(_, _) => ()
    case Item.CommandExecution(_, _, output, exitCode, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = Some("bash"),
          ok = exitCode.contains(0) && status.isCompleted,
          content = output
        )
      )
    case Item.FileChange(_, changes, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = Some("file_change"),
          ok = status.isCompleted,
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
          ok = status.isCompleted,
          content = result.getOrElse("")
        )
      )
    case Item.Other(_, _) =>
      ()

  /** User-facing tool name from codex's `(server, tool)` pair. The dotted form
    * stays distinct from the bare `bash` / `file_change` names of codex's
    * built-in items.
    */
  private def mcpToolName(server: String, tool: String): String =
    s"$server.$tool"

  private def handleTurnCompleted(usage: Usage): Unit =
    settleSuccess(
      wireId = sessionId,
      output = lastAgentMessage,
      usage = usage,
      // Fall back to the configured model when the wire omitted it (e.g. on a
      // resume) so the turn's tokens are priced, not attributed to `(unknown)`.
      modelId = model.orElse(configuredModel.map(_.name))
    )

  /** Mid-turn protocol error (e.g. an invalid model, a provider-side
    * rejection). Not terminal by itself — codex normally follows it with a
    * `turn.failed` event ([[handleTurnFailed]]) — but stashed either way so a
    * bare process exit without a `turn.failed` still surfaces it via
    * [[diagnosticContext]].
    */
  private def handleError(message: String): Unit =
    lastProtocolError = Some(message)
    eventQueue.enqueue(ConversationEvent.Error(s"codex: $message"))

  /** `turn.failed` replaces `turn.completed` when the turn didn't succeed —
    * fail the conversation immediately with codex's own message rather than
    * waiting for the process to exit and falling back to the bare "exited with
    * code N" diagnostic.
    */
  private def handleTurnFailed(message: String): Unit =
    lastProtocolError = Some(message)
    failWith(new AgentTurnFailed(appendContext(s"codex turn failed: $message")))

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
