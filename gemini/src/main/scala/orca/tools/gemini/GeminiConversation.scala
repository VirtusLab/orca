package orca.tools.gemini

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.Usage
import orca.{AgentTurnFailed, OrcaFlowException}
import orca.backend.{
  BufferedStderrDiagnostics,
  ConversationEvent,
  AgentResult,
  ForkedConversation,
  StreamSource
}
import orca.backend.mcp.{AskUserMcpServer, AskUserSession}
import orca.subprocess.PipedCliProcess
import orca.tools.gemini.jsonl.InboundEvent
import ox.Ox

import java.util.concurrent.atomic.AtomicReference

/** Drives a `gemini -p <prompt> --output-format stream-json` session to
  * completion. Boilerplate lives in [[orca.backend.ForkedConversation]]; this
  * class supplies the gemini-specific protocol translation: JSONL →
  * [[InboundEvent]] → `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate — see ADR 0015):
  *   - gemini emits whole `message` chunks, not negotiated tool approvals;
  *     `approval-mode` is pre-baked into spawn args, so `ApproveTool` is never
  *     emitted here.
  *   - gemini headless is one-shot; multi-turn happens via `--resume` on a
  *     fresh spawn, so `sendUserMessage` is a no-op.
  *   - **`AgentResult.output` is synthesised**: gemini has no single terminal
  *     message carrying the answer, so assistant-role `message` content is
  *     accumulated as it streams and the result builder reads it at the
  *     `result` event. The prompt template makes the closing content JSON in
  *     structured mode.
  */
private[gemini] class GeminiConversation(
    process: PipedCliProcess,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    override val askUser: Option[AskUserSession] = None
)(using Ox)
    extends ForkedConversation[BackendTag.Gemini.type](
      source = StreamSource.fromProcess(process),
      backendName = "gemini",
      initialPrompt = initialPrompt
    )
    with BufferedStderrDiagnostics[BackendTag.Gemini.type]:

  private val sessionIdRef = new AtomicReference[String]("")
  private val modelRef = new AtomicReference[Option[String]](None)

  /** Accumulated assistant-role `message` content — the synthesised answer. See
    * the class scaladoc for why we build rather than receive.
    */
  private val answer = new StringBuilder

  /** Maps a tool_use `tool_id` to the `tool_name` it announced, so the matching
    * `tool_result` (which only carries the id) can be keyed by name in the
    * emitted [[ConversationEvent.ToolResult]]. Single-threaded reader (the
    * JSONL reader thread is the sole writer), so a plain `var` is enough.
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

  /** Surface a real stderr line as an `Error` event AND record it in the
    * bounded buffer ([[BufferedStderrDiagnostics]]) so the failure exception
    * can include it. Known-benign chatter gemini prints on every successful run
    * (see [[GeminiConversation.isKnownStderrNoise]]) is dropped so it doesn't
    * render as a spurious `✖` on each call.
    */
  override protected def handleStderr(line: String): Unit =
    val trimmed = line.trim
    if trimmed.nonEmpty && !GeminiConversation.isKnownStderrNoise(trimmed) then
      eventQueue.enqueue(ConversationEvent.Error(s"gemini: $trimmed"))
      recordStderr(trimmed)

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      appendContext(
        "gemini exited cleanly but never sent a result event"
      )
    )

  // --- Per-event dispatch ---

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.Init(sessionId, model) =>
      sessionIdRef.set(sessionId)
      modelRef.set(model)
    case InboundEvent.Message(role, content) => handleMessage(role, content)
    case InboundEvent.ToolUse(name, id, params) =>
      handleToolUse(name, id, params)
    case InboundEvent.ToolResult(id, status, output) =>
      handleToolResult(id, status, output)
    case InboundEvent.Error(message) =>
      eventQueue.enqueue(ConversationEvent.Error(s"gemini: $message"))
    case InboundEvent.Result(usage, status) =>
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
      handleResult(usage, status)
    case InboundEvent.Unknown(_) =>
      // Forward-compat: gemini may add new top-level event types; drop them
      // silently rather than rendering an error.
      ()

  /** Settle the conversation outcome at the terminal `result` event.
    * `"success"` is the documented good status (headless stream-json); an
    * empty/absent status is also treated as success for robustness. Any other
    * non-empty status is a failed turn that must NOT be reported as success
    * even though gemini exited 0 — tagged `AgentTurnFailed` so the autonomous
    * retry loop doesn't reopen the now-registered session id.
    */
  private def handleResult(usage: Usage, status: String): Unit =
    if status.nonEmpty && status != "success" then
      // Fold in the buffered stderr (the real reason — quota, auth, …) so the
      // exception carries it even for a noop listener, matching the
      // non-zero-exit and missing-result failure paths.
      failWith(
        new AgentTurnFailed(
          appendContext(s"gemini turn ended with status '$status'")
        )
      )
    else
      val result = AgentResult(
        wireId = WireSessionId[BackendTag.Gemini.type](sessionIdRef.get()),
        output = answer.toString,
        usage = usage,
        model = modelRef.get().map(Model.apply)
      )
      succeedWith(result)

  /** A `user`-role message is the prompt echo (the base already surfaced the
    * opening prompt as a `UserMessage`), so it's dropped from both the event
    * stream and the answer. Any other role is treated as agent output — gemini
    * spells the assistant side `model`/`assistant` across versions, so we match
    * on "not user" rather than a fixed string.
    */
  private def handleMessage(role: String, content: String): Unit =
    if role != "user" && content.nonEmpty then
      val _ = answer.append(content)
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(content))

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
          toolName = toolNames.getOrElse(id, id),
          ok = status == "success",
          content = output
        )
      )

private[gemini] object GeminiConversation:

  /** The `ask_user` MCP tool as gemini names it in `tool_use` events: gemini
    * qualifies an MCP tool as `<server>__<tool>` (e.g. `orca__ask_user`). Match
    * that exact name (or the bare slug) rather than any name *containing*
    * `ask_user`, so an unrelated tool whose name merely includes the slug isn't
    * suppressed.
    */
  private def isAskUserTool(name: String): Boolean =
    name == AskUserMcpServer.ToolSlug ||
      name == s"${AskUserMcpServer.ServerName}__${AskUserMcpServer.ToolSlug}"

  /** Stderr lines gemini prints on every (successful) headless run that carry
    * no diagnostic value — filtered before they reach the event queue so they
    * don't render as spurious `✖` errors. Observed on gemini 0.45.2:
    *
    *   - a 256-color terminal-capability warning,
    *   - `YOLO mode is enabled. …` notices (we always pass `--approval-mode
    *     yolo` for non-read-only turns),
    *   - `Shell cwd was reset to …` after a tool run,
    *   - `[IDEClient]` companion-extension chatter (gemini probes for a VS Code
    *     companion that an orca-driven subprocess never has).
    */
  private[gemini] def isKnownStderrNoise(line: String): Boolean =
    line.contains("256-color support not detected") ||
      line.startsWith("YOLO mode is enabled") ||
      line.startsWith("Shell cwd was reset to") ||
      line.contains("[IDEClient]")
