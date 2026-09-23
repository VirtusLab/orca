package orca.tools.gemini

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
import orca.tools.gemini.jsonl.{InboundEvent, Role, ToolStatus}

import ox.Ox

/** Decodes a `gemini -p <prompt> --output-format stream-json` session: JSONL →
  * [[InboundEvent]] → `ConversationEvent`s.
  *
  * Gemini specifics (see ADR 0015):
  *   - `approval-mode` is pre-baked into spawn args, so `ApproveTool` is never
  *     emitted.
  *   - headless is one-shot; multi-turn happens via `--resume` on a fresh
  *     spawn.
  *   - `AgentResult.output` is synthesised: gemini has no single terminal
  *     message carrying the answer, so assistant-role `message` content is
  *     accumulated as it streams and read at the `result` event.
  */
private[gemini] object GeminiDecoder
    extends LineDecoder[BackendTag.Gemini.type, GeminiDecoder.State]:

  /** @param answer
    *   assistant-role `message` content, the synthesised answer
    * @param toolNames
    *   the `tool_name` each `tool_id` announced, since the matching
    *   `tool_result` carries only the id
    * @param echoes
    *   `ask_user` tool ids whose echo is dropped — the host-side bridge already
    *   surfaced the `UserQuestion`
    */
  final case class State(
      sessionId: Option[String],
      model: Option[String],
      answer: Vector[String],
      toolNames: Map[String, String],
      echoes: AskUserEchoes
  )

  private type Out = Step[BackendTag.Gemini.type, State]

  def backendName: String = "gemini"

  def terminalMessageNoun: String = "a result event"

  def init: State =
    State(None, None, Vector.empty, Map.empty, AskUserEchoes.empty)

  def line(state: State, line: String): Out =
    InboundEvent.parse(line) match
      case InboundEvent.Init(sessionId, model) =>
        Step.continue(state.copy(sessionId = Some(sessionId), model = model))
      case InboundEvent.Message(role, content) => message(state, role, content)
      case InboundEvent.ToolUse(name, id, params) =>
        toolUse(state, name, id, params)
      case InboundEvent.ToolResult(id, status, output) =>
        toolResult(state, id, status, output)
      case InboundEvent.Error(message) =>
        Step.continue(state, ConversationEvent.Error(s"gemini: $message"))
      case InboundEvent.Result(usage, status) => result(state, usage, status)
      // Forward-compat: gemini may add new top-level event types; drop them
      // silently rather than rendering an error.
      case InboundEvent.Unknown(_) => Step.continue(state)

  /** Known-benign chatter gemini prints on every successful run (see
    * [[GeminiConversation.isKnownStderrNoise]]).
    */
  override def isStderrNoise(line: String): Boolean =
    GeminiConversation.isKnownStderrNoise(line)

  /** Only the terminal `result` event carries stats, and a turn that reaches it
    * settles itself with an `Observed` debit.
    */
  def failedTurnDebit(state: State): TurnDebit = TurnDebit.Unobserved

  /** `result` is terminal. Only an explicit `"success"` is success
    * ([[ToolStatus.isSuccess]]); any other status is a failed turn even though
    * gemini exited 0. A success with no `init` event has no session id to
    * resume, so it fails too.
    */
  private def result(state: State, usage: Usage, status: ToolStatus): Out =
    // The failed `result` frame carries the turn's stats; without them a
    // quota- or max-turns-killed turn spends invisibly.
    val debit = TurnDebit.Observed(usage, state.model.map(Model.apply))
    val settled = (status, state.sessionId) match
      case (ToolStatus.Success, Some(sessionId)) =>
        Settled.Succeeded(
          AgentResult[BackendTag.Gemini.type](
            WireSessionId(sessionId),
            state.answer.mkString,
            usage,
            state.model.map(Model.apply)
          )
        )
      case (ToolStatus.Success, None) =>
        Settled.Failed(
          "gemini completed the turn without an init event, so it has no " +
            "session id to resume",
          debit
        )
      case (ToolStatus.Failure(raw), _) =>
        val description =
          if raw.isEmpty then "a missing status" else s"status '$raw'"
        Settled.Failed(s"gemini turn ended with $description", debit)
    Step.Settle(state, Nil, settled)

  /** A `user`-role message is the prompt echo, so it's dropped.
    * [[Role.Assistant]] (any present role other than `"user"`) is agent output.
    * [[Role.Unknown]] (a missing `role` key) is dropped — never treated as
    * assistant prose.
    */
  private def message(state: State, role: Role, content: String): Out =
    role match
      case Role.User                         => Step.continue(state)
      case Role.Assistant if content.isEmpty => Step.continue(state)
      case Role.Assistant =>
        Step.continue(
          state.copy(answer = state.answer :+ content),
          ConversationEvent.AssistantTextDelta(content)
        )
      case Role.Unknown =>
        StreamConversation.trace(
          backendName,
          "message",
          s"dropped: message with missing/unrecognized role (${content.length} chars)"
        )
        Step.continue(state)

  private def toolUse(
      state: State,
      name: String,
      id: String,
      params: String
  ): Out =
    if GeminiConversation.isAskUserTool(name) then
      Step.continue(state.copy(echoes = state.echoes.suppress(id)))
    else
      Step.continue(
        state.copy(toolNames = state.toolNames + (id -> name)),
        ConversationEvent.AssistantToolCall(toolName = name, rawInput = params)
      )

  private def toolResult(
      state: State,
      id: String,
      status: ToolStatus,
      output: String
  ): Out =
    state.echoes.consume(id) match
      case Some(rest) => Step.continue(state.copy(echoes = rest))
      case None =>
        Step.continue(
          state,
          ConversationEvent.ToolResult(
            toolName = Some(state.toolNames.getOrElse(id, id)),
            ok = status.isSuccess,
            content = output
          )
        )

private[gemini] object GeminiConversation:

  /** Starts decoding `process` into the caller's turn scope. */
  def apply(
      process: PipedCliProcess,
      initialPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: Option[AskUserSession] = None
  )(using Ox): Conversation[BackendTag.Gemini.type] =
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
    )(_ => GeminiDecoder)

  /** The `ask_user` MCP tool as gemini names it in `tool_use` events. gemini
    * qualifies an MCP tool as `<server>__<tool>` (e.g. `orca__ask_user`); match
    * that exact name (or the bare slug) rather than any name *containing*
    * `ask_user`, so an unrelated tool isn't suppressed.
    */
  private[gemini] def isAskUserTool(name: String): Boolean =
    name == AskUserMcpServer.ToolSlug ||
      name == s"${AskUserMcpServer.ServerName}__${AskUserMcpServer.ToolSlug}"

  /** Stderr lines gemini prints on every successful headless run that carry no
    * diagnostic value — filtered so they don't render as spurious `✖` errors.
    * Observed on gemini 0.45.2:
    *
    *   - a 256-color terminal-capability warning,
    *   - `YOLO mode is enabled. …` notices,
    *   - `Shell cwd was reset to …` after a tool run,
    *   - `[IDEClient]` companion-extension probe chatter.
    */
  private[gemini] def isKnownStderrNoise(line: String): Boolean =
    line.contains("256-color support not detected") ||
      line.startsWith("YOLO mode is enabled") ||
      line.startsWith("Shell cwd was reset to") ||
      line.contains("[IDEClient]")
