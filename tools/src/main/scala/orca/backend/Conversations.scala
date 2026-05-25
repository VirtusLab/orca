package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag

/** Drains a [[Conversation]] for the autonomous path: walks every
  * [[ConversationEvent]] off the iterator (the read loop only terminates once
  * the subprocess finishes), emits a matching [[OrcaEvent]] to the listener for
  * the user-visible ones, then returns the awaited `LlmResult`.
  *
  * Mapping:
  *   - `AssistantToolCall(name, raw)` → `OrcaEvent.ToolUse(name, truncated)`,
  *     so the user sees a one-line marker as each tool fires.
  *   - `AssistantTextDelta` buffered per turn, flushed as one
  *     `OrcaEvent.AssistantMessage` on `AssistantTurnEnd` (when non-empty). Any
  *     unflushed buffer at end-of-stream is flushed too so partial output from
  *     a mid-turn subprocess crash isn't silently dropped.
  *   - `AssistantThinkingDelta` dropped — internal-monologue noise isn't useful
  *     in the autonomous log.
  *   - `ConversationEvent.Error` re-emits as `OrcaEvent.Error` so subprocess
  *     stderr makes it into the user log.
  *   - `ToolResult` / `UserMessage` / `ApproveTool` / `UserQuestion` are
  *     swallowed — autonomous calls don't render tool results (the matching
  *     ToolUse already showed something happened), don't echo their own prompt,
  *     and don't expose approval/ask_user flows.
  *
  * `awaitResult()`'s `Left(OrcaInteractiveCancelled)` becomes a thrown
  * `OrcaInteractiveCancelled` so autonomous callers — which never expose a
  * cancel button — don't have to special-case a value they could never have
  * produced. Genuine backend failures (non-zero exit, missing turn-completed,
  * etc.) already surface as thrown [[orca.OrcaFlowException]]s from inside the
  * conversation's reader loop.
  */
private[orca] object Conversations:

  /** Cap for the inline tool-input summary. Matches the live renderer's
    * MaxInlineInputLength so autonomous and interactive logs look alike.
    */
  private val MaxToolInputLength: Int = 120

  /** Pre-compiled — `String.replaceAll` would recompile this on every call,
    * which fires once per agent tool use (many per turn on a busy session).
    */
  private val WhitespaceRun: java.util.regex.Pattern =
    java.util.regex.Pattern.compile("\\s+")

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[B] =
    val textBuf = new StringBuilder
    def flushText(): Unit =
      if textBuf.nonEmpty then
        events.onEvent(OrcaEvent.AssistantMessage(textBuf.toString))
        textBuf.clear()
    conv.events.foreach:
      case ConversationEvent.AssistantToolCall(name, raw) =>
        events.onEvent(OrcaEvent.ToolUse(name, summariseToolInput(raw)))
      case ConversationEvent.AssistantTextDelta(delta) =>
        val _ = textBuf.append(delta)
      case ConversationEvent.AssistantThinkingDelta(_) => ()
      case ConversationEvent.AssistantTurnEnd          => flushText()
      case ConversationEvent.Error(msg) =>
        events.onEvent(OrcaEvent.Error(msg))
      // Tool results, user-message echoes, approval / user-question
      // prompts: not relevant to the autonomous log. Approval and
      // ask_user shouldn't ever reach an autonomous drain (no MCP, all
      // tools pre-approved) — if they do, drop rather than crash so the
      // result still flows.
      case _ => ()
    // Recover partial output if the stream ended mid-turn (deltas arrived
    // but no AssistantTurnEnd before EOF). No-op for well-formed turns
    // since the TurnEnd case already cleared the buffer.
    flushText()
    conv.awaitResult() match
      case Right(result)   => result
      case Left(cancelled) =>
        // Autonomous callers can't cancel — a `Left` here would have to come
        // from a peer thread that has its hands on the conversation, which
        // is not how autonomous calls are wired. Surface as a throw so the
        // call shape (returns `LlmResult`) is honoured.
        throw cancelled

  /** Trim a tool-input blob to one line and cap its length. Cheap and
    * good-enough — the live renderer's `ToolInputSummary` does a fancier
    * field-extraction summary but lives in the runner module. The autonomous
    * log doesn't need that fidelity.
    */
  private def summariseToolInput(raw: String): String =
    val collapsed = WhitespaceRun.matcher(raw).replaceAll(" ").trim
    if collapsed.length <= MaxToolInputLength then collapsed
    else s"${collapsed.take(MaxToolInputLength - 1)}…"
