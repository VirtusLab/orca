package orca.backend

import orca.agents.BackendTag
import orca.events.{OrcaEvent, OrcaListener}
import ox.either.orThrow

/** A live [[Conversation]] whose display events go to an [[OrcaListener]], on
  * both the autonomous and the interactive path, leaving the consumer only the
  * [[ChannelEvent]]s it must answer.
  *
  * [[drain]] maps each conversation event to what listeners see: assistant
  * prose to `OrcaEvent.AssistantMessage` (one per completed turn), tool calls
  * to `ToolUse`, refusals to `ToolDenied`, errors to `Error`, and the opening
  * prompt to `UserPrompt`. Tool results are dropped: their volume is unbounded
  * (full cargo-test logs, etc.), and the matching `ToolUse` already went out.
  *
  * A structured call withholds its closing assistant turn — the caller emits
  * the result via `OrcaEvent.StructuredResult` instead. Where the payload
  * arrives as reply text (`StructuredOutputMode.RawText`) that turn IS the
  * JSON; where it arrives as a tool call (claude's `--json-schema` exit call,
  * `StructuredOutputMode.Tool`) it is the model's sign-off restating what the
  * `StructuredResult` states in full. Both `Tool`-mode drivers suppress the
  * exit call itself (`ClaudeConversation.handleAssistantTurn`,
  * `OpencodeConversation.isStructuredOutputEcho`), so no turn-opening event
  * follows the sign-off to release it. Mid-turn narration is unaffected — a
  * real tool call still releases the turn that announced it. The interactive
  * path withholds it too: `Prompts.interactive` asks every backend for a
  * JSON-only final message.
  */
final class ObservedConversation[B <: BackendTag] private[orca] (
    conv: Conversation[B],
    listener: OrcaListener
):
  import ObservedConversation.*

  /** Consume the conversation to its end, passing each [[ChannelEvent]] to
    * `answer` as it arrives, then return the result. Call once. `answer` must
    * call the event's `respond` (or [[cancel]]): the backend blocks until it
    * does. Throws [[orca.OrcaInteractiveCancelled]] if the turn was cancelled.
    */
  def drain(answer: ChannelEvent => Unit): AgentResult[B] =
    val buffer = new TurnBuffer(
      closingProse(conv),
      text => listener.onEvent(OrcaEvent.AssistantMessage(text))
    )
    try
      conv.events.foreach: event =>
        if event.opensTurn then buffer.onActivity()
        event match
          case ConversationEvent.AssistantToolCall(name, raw) =>
            listener.onEvent(OrcaEvent.ToolUse(name, raw))
          case ConversationEvent.AssistantTextDelta(delta) =>
            buffer.append(delta)
          case ConversationEvent.AssistantThinkingDelta(_) => ()
          case ConversationEvent.AssistantTurnEnd          => buffer.turnEnd()
          case ConversationEvent.ToolResult(_, _, _)       => ()
          case ConversationEvent.ToolDenied(toolName) =>
            listener.onEvent(OrcaEvent.ToolDenied(toolName, None))
          case ConversationEvent.Error(message) =>
            listener.onEvent(OrcaEvent.Error(message))
          case ConversationEvent.UserMessage(text) =>
            listener.onEvent(OrcaEvent.UserPrompt(text))
          case e: ConversationEvent.ApproveTool  => answer(e)
          case e: ConversationEvent.UserQuestion => answer(e)
      buffer.finishNormally()
    catch
      case t: Throwable =>
        buffer.finishAbnormally()
        throw t
    conv.awaitResult().orThrow

  /** See [[Conversation.cancel]]. */
  def cancel(): Unit = conv.cancel()

private[orca] object ObservedConversation:

  /** Whether the drain renders a conversation's closing assistant prose turn,
    * or keeps it out of the prose stream because it carries the structured
    * payload.
    */
  private enum ClosingProse:
    case Withhold, Render

  /** A structured call withholds its closing prose turn; a call with no schema
    * renders it. The wire's `StructuredOutputMode` doesn't come into it (see
    * the class scaladoc).
    */
  private def closingProse(conv: Conversation[?]): ClosingProse =
    if conv.outputSchema.isDefined then ClosingProse.Withhold
    else ClosingProse.Render

  /** Renders each completed assistant turn's prose as one `emit`, holding back
    * the CLOSING turn when it carries the structured payload.
    *
    * A turn can only be recognised as closing after the fact, so a completed
    * turn is parked in `withheld` and released by [[onActivity]] — the first
    * assistant event of the next turn — which keeps a turn's narration ahead of
    * the tool calls it announces. What survives to end-of-stream is the closing
    * turn, and only that is dropped. A [[ChannelEvent]] opens no turn, so it is
    * never stuck behind a withheld one.
    *
    * State is confined to the drain's single-threaded event loop — the mutable
    * `StringBuilder`/`var` here is a deliberate, reviewed deviation from the
    * codebase's Ox-concurrency default, not an actor oversight: this runs on
    * one thread only, so no channel/actor would buy anything.
    */
  private final class TurnBuffer(closing: ClosingProse, emit: String => Unit):
    private val current = new StringBuilder
    private var withheld: Option[String] = None

    /** Assistant activity opening a turn (`ConversationEvent.opensTurn`): the
      * parked turn isn't the closing one after all, so release it. Call before
      * rendering the triggering event, so a turn's narration lands ahead of the
      * tool call it announces.
      */
    def onActivity(): Unit =
      withheld.foreach(emit)
      withheld = None

    def append(delta: String): Unit =
      val _ = current.append(delta)

    def turnEnd(): Unit =
      if current.nonEmpty then
        val text = current.toString
        current.clear()
        closing match
          case ClosingProse.Withhold => withheld = Some(text)
          case ClosingProse.Render   => emit(text)

    /** Normal end of stream: the parked turn IS the payload — drop it (the
      * caller emits StructuredResult); flush any unfinished current buffer.
      *
      * Since StreamConversation closes every completed turn, a normal session
      * ends its last turn with an `AssistantTurnEnd` that already ran
      * `turnEnd()`, leaving `current` empty. `flushCurrent()` is a safety net
      * for a turn the stream left open (abnormal termination mid-turn).
      */
    def finishNormally(): Unit =
      withheld = None
      flushCurrent()

    /** The drain threw — reading the stream or answering an event: the turn
      * boundary that would have told the payload from narration never arrived,
      * so flush everything rather than drop prose. Worst case the user sees a
      * JSON blob once.
      *
      * A failure the wire reports (an `is_error` result) closes the stream
      * normally, so [[finishNormally]] runs and the parked turn is dropped like
      * any other closing turn — an unfinished one still flushes from `current`.
      */
    def finishAbnormally(): Unit =
      withheld.foreach(emit)
      withheld = None
      flushCurrent()

    private def flushCurrent(): Unit =
      if current.nonEmpty then
        emit(current.toString)
        current.clear()
