package orca.tools.gemini

import orca.agents.BackendTag
import orca.events.Usage
import orca.AgentTurnFailed
import orca.backend.{
  StderrPipeline,
  ConversationEvent,
  ForkedConversation,
  StreamSource
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.PipedCliProcess
import orca.tools.gemini.jsonl.{InboundEvent, Role}

/** Drives a `gemini -p <prompt> --output-format stream-json` session to
  * completion. Boilerplate lives in [[orca.backend.ForkedConversation]]; this
  * class supplies the gemini-specific translation: JSONL → [[InboundEvent]] →
  * `ConversationEvent`s.
  *
  * Gemini specifics (see ADR 0015):
  *   - `approval-mode` is pre-baked into spawn args, so `ApproveTool` is never
  *     emitted.
  *   - headless is one-shot; multi-turn happens via `--resume` on a fresh
  *     spawn, so `sendUserMessage` is a no-op.
  *   - `AgentResult.output` is synthesised: gemini has no single terminal
  *     message carrying the answer, so assistant-role `message` content is
  *     accumulated as it streams and read at the `result` event.
  */
private[gemini] class GeminiConversation(
    process: PipedCliProcess,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    override val askUser: Option[AskUserSession] = None
) extends ForkedConversation[BackendTag.Gemini.type](
      source = StreamSource.fromProcess(process),
      backendName = "gemini",
      initialPrompt = initialPrompt
    )
    with StderrPipeline[BackendTag.Gemini.type]:

  // Reader-thread-confined: written and read only from the JSONL reader thread;
  // `awaitResult`'s `readerFork.join()` publishes the final values to the caller.
  private var sessionId: String = ""
  private var model: Option[String] = None

  /** Accumulated assistant-role `message` content — the synthesised answer. See
    * the class scaladoc for why we build rather than receive.
    */
  private val answer = new StringBuilder

  /** Maps a tool_use `tool_id` to the `tool_name` it announced, so the matching
    * `tool_result` (which only carries the id) can be keyed by name in the
    * emitted [[ConversationEvent.ToolResult]].
    */
  private var toolNames: Map[String, String] = Map.empty

  /** tool_use ids for `ask_user` MCP calls whose echo we drop — the host-side
    * bridge already surfaced the matching `UserQuestion`, so rendering the tool
    * call + the answer-as-result on top would be noise. See
    * [[orca.backend.AskUserEchoes]].
    */
  private val askUserEchoes = new orca.backend.AskUserEchoes

  // No `start()`: the base spawns its reader / stderr / ask-user forks lazily
  // on first touch of the conversation surface, after this subclass's fields
  // are initialised.

  // --- Reader hooks ---

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  /** Known-benign chatter gemini prints on every successful run (see
    * [[GeminiConversation.isKnownStderrNoise]]) is dropped so it doesn't render
    * as a spurious `✖`; anything else passes through [[StderrPipeline]].
    */
  override protected def isStderrNoise(line: String): Boolean =
    GeminiConversation.isKnownStderrNoise(line)

  override protected def terminalMessageNoun: String = "a result event"

  // --- Per-event dispatch ---

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.Init(sessionId, model) =>
      this.sessionId = sessionId
      this.model = model
    case InboundEvent.Message(role, content) => handleMessage(role, content)
    case InboundEvent.ToolUse(name, id, params) =>
      handleToolUse(name, id, params)
    case InboundEvent.ToolResult(id, status, output) =>
      handleToolResult(id, status, output)
    case InboundEvent.Error(message) =>
      eventQueue.enqueue(ConversationEvent.Error(s"gemini: $message"))
    case InboundEvent.Result(usage, status) =>
      // `result` is terminal: the base funnel auto-closes any open turn when
      // `handleResult` settles (and drops the turn end when the turn was empty),
      // so nothing is emitted here.
      handleResult(usage, status)
    case InboundEvent.Unknown(_) =>
      // Forward-compat: gemini may add new top-level event types; drop them
      // silently rather than rendering an error.
      ()

  /** Settle the conversation outcome at the terminal `result` event.
    * `"success"` (and an empty/absent status) is success; any other non-empty
    * status is a failed turn that must NOT be reported as success even though
    * gemini exited 0 — tagged `AgentTurnFailed` so the autonomous retry loop
    * doesn't reopen the now-registered session id.
    */
  private def handleResult(usage: Usage, status: String): Unit =
    if status.nonEmpty && status != "success" then
      // Fold in the buffered stderr (the real reason — quota, auth, …) so the
      // exception carries it even for a noop listener.
      failWith(
        new AgentTurnFailed(
          appendContext(s"gemini turn ended with status '$status'")
        )
      )
    else
      settleSuccess(
        wireId = sessionId,
        output = answer.toString,
        usage = usage,
        modelId = model
      )

  /** A `user`-role message is the prompt echo, so it's dropped.
    * [[Role.Assistant]] (any present role other than `"user"`) is agent output.
    * [[Role.Unknown]] (a missing `role` key) is dropped — never treated as
    * assistant prose.
    */
  private def handleMessage(role: Role, content: String): Unit = role match
    case Role.User => ()
    case Role.Assistant =>
      if content.nonEmpty then
        val _ = answer.append(content)
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(content))
    case Role.Unknown =>
      debugLog(
        "message",
        s"dropped: message with missing/unrecognized role (${content.length} chars)"
      )

  private def handleToolUse(name: String, id: String, params: String): Unit =
    if GeminiConversation.isAskUserTool(name) then askUserEchoes.suppress(id)
    else
      toolNames = toolNames + (id -> name)
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(toolName = name, rawInput = params)
      )

  private def handleToolResult(
      id: String,
      status: String,
      output: String
  ): Unit =
    if askUserEchoes.consume(id) then ()
    else
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = Some(toolNames.getOrElse(id, id)),
          ok = status == "success",
          content = output
        )
      )

private[gemini] object GeminiConversation:

  /** The `ask_user` MCP tool as gemini names it in `tool_use` events. gemini
    * qualifies an MCP tool as `<server>__<tool>` (e.g. `orca__ask_user`); match
    * that exact name (or the bare slug) rather than any name *containing*
    * `ask_user`, so an unrelated tool isn't suppressed.
    */
  private def isAskUserTool(name: String): Boolean =
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
