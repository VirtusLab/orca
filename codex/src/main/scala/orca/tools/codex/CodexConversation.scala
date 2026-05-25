package orca.tools.codex

import orca.llm.{BackendTag, Model, SessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, LlmResult}
import orca.backend.StreamConversation
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

import java.util.concurrent.atomic.AtomicReference

/** Drives a `codex exec --json` session to completion.
  *
  * Boilerplate (reader thread, event queue, outcome lifecycle, stderr drain)
  * lives in [[StreamConversation]]; this class supplies the codex-specific
  * protocol translation: JSONL → [[InboundEvent]] → `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate, driven by codex's JSONL protocol
  * — see [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]]):
  *   - codex emits whole `agent_message` items, not per-token deltas. Each
  *     agent message becomes one [[ConversationEvent.AssistantTextDelta]]
  *     followed by [[ConversationEvent.AssistantTurnEnd]].
  *   - codex doesn't negotiate tool approvals over the JSONL stream;
  *     `LlmConfig.autoApprove` is pre-baked into the spawn args by
  *     [[CodexArgs]]. [[ConversationEvent.ApproveTool]] is never emitted from
  *     this driver.
  *   - `codex exec` reads one prompt and exits; multi-turn happens by spawning
  *     a fresh `codex exec resume`. So `sendUserMessage` is a no-op (write to a
  *     closed pipe) — the channel reaches the next turn via
  *     [[orca.LlmBackend.continueInteractive]].
  *   - **`LlmResult.output` is synthesised, not delivered.** ADR 0006's "the
  *     final result message carries the structured output" description fits
  *     claude (an explicit `result` line on stdout with a `result` field).
  *     codex has no equivalent terminal message — it just emits
  *     `turn.completed` with usage. We treat the *last* `item.completed` of
  *     type `agent_message` before `turn.completed` as the structured payload,
  *     snapshotting its text into [[lastAgentMessage]] on every agent message
  *     and building the `LlmResult` from that snapshot when the turn closes.
  *     The `DefaultPromptTemplate` already instructs the agent to make its
  *     final message JSON-only, so the snapshot contract holds in practice.
  */
private[codex] class CodexConversation(
    process: PipedCliProcess,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None
) extends StreamConversation[BackendTag.Codex.type](
      process = process,
      backendName = "codex",
      initialPrompt = initialPrompt
    ):

  import CodexConversation.*
  import StreamConversation.Outcome

  private val sessionIdRef = new AtomicReference[String]("")
  private val modelRef = new AtomicReference[Option[String]](None)

  /** The most recent agent_message text the model produced. See the class
    * scaladoc for why we synthesise rather than receive.
    */
  private val lastAgentMessage = new AtomicReference[String]("")

  /** Bounded ring of trimmed stderr lines (post-filter), kept so a non-zero
    * exit or clean-exit-without-result can carry the actual failure context
    * back to the caller in the thrown exception — listener subscribers already
    * saw each line as a `ConversationEvent.Error`, but a noop listener (tests,
    * simple scripts) would otherwise lose the diagnostic entirely. Capped on
    * both line count and byte total so a runaway agent can't OOM us.
    */
  private val stderrBuffer = new AtomicReference[Vector[String]](Vector.empty)

  // Subclass fields above are assigned now; safe to spin up the reader +
  // stderr workers. See [[StreamConversation.start]].
  start()

  // --- Conversation surface ---

  /** Codex exec consumes its prompt argv-side and ignores stdin thereafter;
    * injecting more user turns mid-session isn't supported. The contract still
    * requires a callable method — this is a no-op.
    */
  def sendUserMessage(text: String): Unit = ()

  // codex exec consumes stdin once and has no mid-session user-message
  // channel (ADR 0007); a fresh process is needed for follow-up turns.
  // Flows that depend on mid-session Q&A must run on Claude.
  def canAskUser: Boolean = false

  // --- Reader hooks ---

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  /** codex prints `Reading additional input from stdin...` on every exec
    * invocation when stdin is piped (regardless of whether we actually feed it
    * anything). Filter that and empty lines; pass the rest through with the
    * default backend-prefixed Error event AND append to the bounded
    * [[stderrBuffer]] so the failure exception can include them.
    */
  override protected def handleStderr(line: String): Unit =
    val trimmed = line.trim
    if trimmed.nonEmpty &&
      !trimmed.startsWith("Reading additional input from stdin")
    then
      eventQueue.enqueue(ConversationEvent.Error(s"codex: $trimmed"))
      val _ = stderrBuffer.updateAndGet(appendBounded(_, trimmed))

  /** Bounded wait for the stderr drain so any trailing error lines (which the
    * consumer can't see once we close the queue) reach the events queue. The
    * cap is short — by the time stdout EOFs, the process has exited and stderr
    * should EOF nearly simultaneously; if it doesn't, we'd rather lose a late
    * stderr line than hold `awaitResult` hostage on a stalled child.
    */
  override protected def onFinalize(): Unit =
    try stderrDrainThread.join(StderrDrainTimeoutMs)
    catch
      // Interrupt while joining means the parent is shutting down.
      // Restore the flag so callers up-stack can react.
      case _: InterruptedException => Thread.currentThread().interrupt()

  override protected def cleanExitWithoutResult(): Throwable =
    // Defer the framing to the base so the diagnosticContext below gets
    // folded in with the same shape as the non-zero-exit branch.
    new OrcaFlowException(
      appendContext(
        "codex exited cleanly but never sent a turn.completed event"
      )
    )

  /** Recent stderr lines, formatted as a `stderr:` block. The base layer
    * ([[StreamConversation.appendContext]]) owns the leading-newline + indent
    * framing so this override returns just the payload.
    */
  override protected def diagnosticContext: Option[String] =
    val lines = stderrBuffer.get()
    if lines.isEmpty then None
    else Some(lines.mkString("stderr:\n    ", "\n    ", ""))

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
    case Item.Other(_, _) =>
      ()

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

  /** Bounded wait for the stderr thread to finish draining before the event
    * queue is closed. Long enough that real EOF-after-process- exit lands;
    * short enough that a stalled child doesn't deadlock `awaitResult`.
    */
  private val StderrDrainTimeoutMs: Long = 500L

  /** Cap on lines kept in [[stderrBuffer]]. Sized for a typical stack trace
    * plus a brief explanation — enough to diagnose a failure inline, bounded so
    * a chatty subprocess can't grow memory.
    */
  private[codex] val StderrMaxLines: Int = 20

  /** Soft byte cap on [[stderrBuffer]], counted across kept lines. Trims from
    * the front (oldest) when exceeded, same as the line cap, so the most recent
    * (typically most diagnostic) lines stay.
    */
  private[codex] val StderrMaxBytes: Int = 4096

  /** Append `line` to `buf` while respecting both caps. Drops from the head
    * (oldest line first) until the result fits. A single line that exceeds
    * `StderrMaxBytes` on its own is kept anyway — one oversized line is more
    * useful than empty diagnostics. Lives on the companion so the
    * `AtomicReference.updateAndGet` callback stays a pure function.
    * `private[codex]` so the cap-edge tests can exercise it directly.
    */
  private[codex] def appendBounded(
      buf: Vector[String],
      line: String
  ): Vector[String] =
    var result = buf :+ line
    while result.size > StderrMaxLines do result = result.tail
    while result.size > 1 && result.map(_.length).sum > StderrMaxBytes do
      result = result.tail
    result

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
