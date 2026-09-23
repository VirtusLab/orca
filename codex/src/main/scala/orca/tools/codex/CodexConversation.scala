package orca.tools.codex

import orca.agents.{BackendTag, Model, StructuredOutputMode, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.backend.{
  AgentResult,
  AskUserChannel,
  AskUserEchoes,
  Conversation,
  ConversationEvent,
  ConversationSpec,
  LineDecoder,
  Settled,
  Step,
  StreamConversation,
  StreamSource
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import ox.Ox

/** Decodes a `codex exec --json` session: JSONL → [[InboundEvent]] →
  * `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate, driven by codex's JSONL protocol
  * — see ADR 0007):
  *   - codex emits whole `agent_message` items, not per-token deltas; each
  *     becomes one `AssistantTextDelta`. Non-structured calls close a turn
  *     (`AssistantTurnEnd`) after every item; structured calls instead coalesce
  *     every `agent_message` into a single turn closed at `turn.completed` —
  *     see [[itemCompleted]].
  *   - codex doesn't negotiate tool approvals over the wire; `autoApprove` is
  *     pre-baked into spawn args. `ApproveTool` is never emitted here.
  *   - `codex exec` is one-shot; multi-turn happens via `codex exec resume` on
  *     a fresh spawn.
  *   - **`AgentResult.output` is synthesised**: codex has no terminal message
  *     carrying the structured payload, so the state keeps the last agent
  *     message and the result is built from it at `turn.completed`. The prompt
  *     template makes the last message JSON.
  *
  * @param configuredModel
  *   names the turn's model when codex's `thread.started` omits it, which
  *   0.145.0 always does — see [[DefaultCodexAgent.Sol]]
  */
private[codex] final class CodexDecoder(
    outputSchema: Option[String],
    configuredModel: Option[Model]
) extends LineDecoder[BackendTag.Codex.type, CodexDecoder.State]:

  import CodexConversation.*
  import CodexDecoder.State

  private type Out = Step[BackendTag.Codex.type, State]

  def backendName: String = "codex"

  def terminalMessageNoun: String = "a turn.completed event"

  def init: State = State(None, None, "", None, AskUserEchoes.empty)

  def line(state: State, line: String): Out =
    InboundEvent.parse(line) match
      case InboundEvent.ThreadStarted(threadId, model) =>
        Step.continue(state.copy(threadId = Some(threadId), model = model))
      case InboundEvent.TurnStarted          => Step.continue(state)
      case InboundEvent.TurnCompleted(usage) => turnCompleted(state, usage)
      case InboundEvent.ItemStarted(item)    => itemStarted(state, item)
      case InboundEvent.ItemCompleted(item)  => itemCompleted(state, item)
      case InboundEvent.Error(message)       => error(state, message)
      case InboundEvent.TurnFailed(message)  => turnFailed(state, message)
      // Forward-compat: codex may add new top-level event types; drop them
      // silently rather than rendering ✖.
      case InboundEvent.Unknown(_) => Step.continue(state)

  /** codex's own `error`/`turn.failed` message, so a bare process exit after
    * one still carries codex's explanation rather than just an exit code.
    */
  override def protocolContext(state: State): Option[String] =
    state.lastProtocolError.map(m => s"codex error:\n    $m")

  /** Known-benign noise codex prints on every exec invocation:
    *
    *   - `Reading additional input from stdin…` whenever stdin is piped (we
    *     always pipe, even though we immediately close it).
    *   - `ERROR codex_core::session: failed to record rollout items: thread
    *     <id> not found` during shutdown, after the rollout writer is torn
    *     down. The rollout file is still written correctly; the message is
    *     harmless.
    */
  override def isStderrNoise(line: String): Boolean =
    isKnownStderrNoise(line)

  /** ADR 0007: codex reports usage on `turn.completed` only — a `turn.failed`
    * or a torn stream carries none, and there is no per-message counter to
    * accrue along the way.
    */
  def failedTurnDebit(state: State): TurnDebit = TurnDebit.Unobserved

  private def itemStarted(state: State, item: Item): Out = item match
    case Item.CommandExecution(_, command, _, _, _) =>
      Step.continue(
        state,
        ConversationEvent.AssistantToolCall(
          toolName = "bash",
          rawInput = writeToString(BashInput(command))
        )
      )
    case Item.FileChange(_, changes, _) =>
      Step.continue(
        state,
        ConversationEvent.AssistantToolCall(
          toolName = "file_change",
          rawInput = writeToString(FileChangeInput(changes.map(toWire)))
        )
      )
    case Item.McpToolCall(id, server, tool, _, _, _)
        if server == AskUserMcpServer.ServerName &&
          tool == AskUserMcpServer.ToolSlug =>
      // ask_user is surfaced through the host-side bridge as a UserQuestion
      // event; the matching item.completed echo is dropped too — the user has
      // already seen their typed answer at the prompt.
      Step.continue(state.copy(echoes = state.echoes.suppress(id)))
    case Item.McpToolCall(_, server, tool, args, _, _) =>
      Step.continue(
        state,
        ConversationEvent.AssistantToolCall(
          toolName = mcpToolName(server, tool),
          rawInput = args
        )
      )
    // agent_message / reasoning announce themselves at completion; other
    // items pass through without a started event.
    case _ => Step.continue(state)

  private def itemCompleted(state: State, item: Item): Out = item match
    case Item.AgentMessage(_, text) =>
      // Structured calls: codex sometimes emits an early "commentary"
      // agent_message — often a verbatim draft of the eventual answer —
      // before finishing its tool calls, then a genuine final one; the wire
      // item shape carries no phase/channel field distinguishing the two
      // (ADR 0007). Leaving the turn open here coalesces every agent_message
      // of the call into ONE ConversationEvent-level turn, closed exactly
      // once when turn.completed settles. Without this, the withholding
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
      Step.Continue(
        state.copy(lastAgentMessage = text),
        ConversationEvent.AssistantTextDelta(text) ::
          Option
            .when(outputSchema.isEmpty)(ConversationEvent.AssistantTurnEnd)
            .toList
      )
    case Item.Reasoning(_, text) if text.nonEmpty =>
      Step.continue(state, ConversationEvent.AssistantThinkingDelta(text))
    case Item.Reasoning(_, _) => Step.continue(state)
    case Item.CommandExecution(_, _, output, exitCode, status) =>
      Step.continue(
        state,
        ConversationEvent.ToolResult(
          toolName = Some("bash"),
          ok = exitCode.contains(0) && status.isCompleted,
          content = output
        )
      )
    case Item.FileChange(_, changes, status) =>
      Step.continue(
        state,
        ConversationEvent.ToolResult(
          toolName = Some("file_change"),
          ok = status.isCompleted,
          content = changes.map(c => s"${c.kind} ${c.path}").mkString("\n")
        )
      )
    case Item.McpToolCall(id, server, tool, _, result, status) =>
      state.echoes.consume(id) match
        // Matched a suppressed ask_user call started above; drop the mirrored
        // completion.
        case Some(rest) => Step.continue(state.copy(echoes = rest))
        case None =>
          Step.continue(
            state,
            ConversationEvent.ToolResult(
              toolName = Some(mcpToolName(server, tool)),
              ok = status.isCompleted,
              content = result.getOrElse("")
            )
          )
    case Item.Other(_, _) => Step.continue(state)

  /** User-facing tool name from codex's `(server, tool)` pair. The dotted form
    * stays distinct from the bare `bash` / `file_change` names of codex's
    * built-in items.
    */
  private def mcpToolName(server: String, tool: String): String =
    s"$server.$tool"

  /** A turn with no `thread.started` ran but can't be resumed, so it fails
    * rather than settling with an id nothing can use.
    */
  private def turnCompleted(state: State, usage: Usage): Out =
    val model = state.model.orElse(configuredModel.map(_.name))
    val settled = state.threadId match
      case Some(threadId) =>
        Settled.Succeeded(
          AgentResult[BackendTag.Codex.type](
            WireSessionId(threadId),
            state.lastAgentMessage,
            usage,
            model.map(Model.apply)
          )
        )
      case None =>
        Settled.Failed(
          "codex completed the turn without a thread.started event, so it " +
            "has no session id to resume",
          TurnDebit.Observed(usage, model.map(Model.apply))
        )
    Step.Settle(state, Nil, settled)

  /** Mid-turn protocol error (e.g. an invalid model, a provider-side
    * rejection). Not terminal by itself — codex normally follows it with a
    * `turn.failed` event — but kept either way so a bare process exit without a
    * `turn.failed` still surfaces it via [[protocolContext]].
    */
  private def error(state: State, message: String): Out =
    Step.continue(
      state.copy(lastProtocolError = Some(message)),
      ConversationEvent.Error(s"codex: $message")
    )

  /** `turn.failed` replaces `turn.completed` when the turn didn't succeed —
    * fail with codex's own message rather than waiting for the process to exit
    * and falling back to the bare "exited with code N" diagnostic.
    */
  private def turnFailed(state: State, message: String): Out =
    val failed = state.copy(lastProtocolError = Some(message))
    Step.Settle(
      failed,
      Nil,
      Settled.Failed(s"codex turn failed: $message", failedTurnDebit(failed))
    )

  private def toWire(c: FileChangeDetail): FileChangeWire =
    FileChangeWire(c.path, c.kind)

private[codex] object CodexDecoder:

  /** @param lastAgentMessage
    *   the synthesised result's output — see the class scaladoc
    * @param lastProtocolError
    *   the last `error`/`turn.failed` message, for [[protocolContext]]
    * @param echoes
    *   ask_user MCP item ids whose echo is dropped — the host-side bridge
    *   already surfaced the `UserQuestion`
    */
  final case class State(
      threadId: Option[String],
      model: Option[String],
      lastAgentMessage: String,
      lastProtocolError: Option[String],
      echoes: AskUserEchoes
  )

private[codex] object CodexConversation:

  /** Starts decoding `process` into the caller's turn scope. */
  def apply(
      process: PipedCliProcess,
      initialPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: Option[AskUserSession] = None,
      configuredModel: Option[Model] = None
  )(using Ox): Conversation[BackendTag.Codex.type] =
    StreamConversation.start(
      StreamSource.fromProcess(process),
      ConversationSpec(
        openingPrompt = initialPrompt,
        outputSchema = outputSchema,
        structuredOutputMode = StructuredOutputMode.RawText,
        askUser =
          askUser.fold(AskUserChannel.Unavailable)(AskUserChannel.Mcp(_)),
        onUnsettledEnd = () => ()
      )
    )(_ => CodexDecoder(outputSchema, configuredModel))

  /** Stderr lines codex emits unconditionally that carry no diagnostic value —
    * filtered before they reach the event queue. See
    * [[CodexDecoder.isStderrNoise]] for what each line means.
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
  private[codex] case class BashInput(command: String)
      derives ConfiguredJsonValueCodec

  private[codex] case class FileChangeWire(path: String, kind: String)
      derives ConfiguredJsonValueCodec

  private[codex] case class FileChangeInput(changes: List[FileChangeWire])
      derives ConfiguredJsonValueCodec
