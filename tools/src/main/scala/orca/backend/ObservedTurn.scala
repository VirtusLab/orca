package orca.backend

import orca.agents.BackendTag
import orca.events.{OrcaEvent, OrcaListener}
import ox.either.orThrow

/** A [[LiveTurn]] whose display events go to an [[OrcaListener]], on both the
  * autonomous and the interactive path, leaving the consumer only the
  * [[ChannelEvent]]s it must answer.
  *
  * [[drain]] maps each turn event to what listeners see: assistant prose to
  * `OrcaEvent.AssistantMessage` (one per completed message), tool calls to
  * `ToolUse`, refusals to `ToolDenied`, errors to `Error`, and the opening
  * prompt to `UserPrompt`. Tool results are dropped: their volume is unbounded
  * (full cargo-test logs, etc.), and the matching `ToolUse` already went out.
  *
  * A structured call withholds its closing assistant message — the caller emits
  * the result via `OrcaEvent.StructuredResult` instead. Where the payload
  * arrives as reply text (`StructuredOutputMode.RawText`) that message IS the
  * JSON; where it arrives as a tool call (claude's `--json-schema` exit call,
  * `StructuredOutputMode.Tool`) it is the model's sign-off restating what the
  * `StructuredResult` states in full. Both `Tool`-mode decoders suppress the
  * exit call itself (`ClaudeDecoder.assistantMessage`,
  * `OpencodeDecoder.isStructuredOutputEcho`), so no message-opening event
  * follows the sign-off to release it. Mid-turn narration is unaffected — a
  * real tool call still releases the message that announced it. The interactive
  * path withholds it too: `Prompts.interactive` asks every backend for a
  * JSON-only final message.
  */
final class ObservedTurn[B <: BackendTag] private[orca] (
    live: LiveTurn[B],
    listener: OrcaListener
):
  import ObservedTurn.*

  /** Consume the turn to its end, passing each [[ChannelEvent]] to `answer` as
    * it arrives, then return the result. Call once. `answer` must call the
    * event's `respond` (or [[cancel]]): the backend blocks until it does.
    * Throws [[orca.OrcaInteractiveCancelled]] if the turn was cancelled.
    */
  def drain(answer: ChannelEvent => Unit): AgentResult[B] =
    val buffer = new MessageBuffer(
      closingProse(live),
      text => listener.onEvent(OrcaEvent.AssistantMessage(text))
    )
    try
      live.events.foreach: event =>
        if event.opensMessage then buffer.onActivity()
        event match
          case TurnEvent.AssistantToolCall(name, raw) =>
            listener.onEvent(OrcaEvent.ToolUse(name, raw))
          case TurnEvent.AssistantTextDelta(delta) =>
            buffer.append(delta)
          case TurnEvent.AssistantThinkingDelta(_) => ()
          case TurnEvent.AssistantMessageEnd       => buffer.messageEnd()
          case TurnEvent.ToolResult(_, _, _)       => ()
          case TurnEvent.ToolDenied(toolName) =>
            listener.onEvent(OrcaEvent.ToolDenied(toolName, None))
          case TurnEvent.Error(message) =>
            listener.onEvent(OrcaEvent.Error(message))
          case TurnEvent.UserMessage(text) =>
            listener.onEvent(OrcaEvent.UserPrompt(text))
          case TurnEvent.Approval(request) => answer(request)
          case TurnEvent.Question(request) => answer(request)
      buffer.finishNormally()
    catch
      case t: Throwable =>
        buffer.finishAbnormally()
        throw t
    live.awaitResult().orThrow

  /** End the turn. Safe to call from any thread. */
  def cancel(): Unit = live.cancel()

private[orca] object ObservedTurn:

  /** Whether the drain renders a turn's closing assistant prose message, or
    * keeps it out of the prose stream because it carries the structured
    * payload.
    */
  private enum ClosingProse:
    case Withhold, Render

  /** A structured call withholds its closing prose message; a call with no
    * schema renders it. The wire's `StructuredOutputMode` doesn't come into it
    * (see the class scaladoc).
    */
  private def closingProse(live: LiveTurn[?]): ClosingProse =
    if live.outputSchema.isDefined then ClosingProse.Withhold
    else ClosingProse.Render

  /** Renders each completed assistant message's prose as one `emit`, holding
    * back the CLOSING message when it carries the structured payload.
    *
    * A message can only be recognised as closing after the fact, so a completed
    * message is parked in `withheld` and released by [[onActivity]] — the first
    * assistant event of the next message — which keeps a message's narration
    * ahead of the tool calls it announces. What survives to end-of-stream is
    * the closing message, and only that is dropped. A [[ChannelEvent]] opens no
    * message, so it is never stuck behind a withheld one.
    *
    * State is confined to the drain's single-threaded event loop — the mutable
    * `StringBuilder`/`var` here is a deliberate, reviewed deviation from the
    * codebase's Ox-concurrency default, not an actor oversight: this runs on
    * one thread only, so no channel/actor would buy anything.
    */
  private final class MessageBuffer(
      closing: ClosingProse,
      emit: String => Unit
  ):
    private val current = new StringBuilder
    private var withheld: Option[String] = None

    /** Assistant activity opening a message (`TurnEvent.opensMessage`): the
      * parked message isn't the closing one after all, so release it. Call
      * before rendering the triggering event, so a message's narration lands
      * ahead of the tool call it announces.
      */
    def onActivity(): Unit =
      withheld.foreach(emit)
      withheld = None

    def append(delta: String): Unit =
      val _ = current.append(delta)

    def messageEnd(): Unit =
      if current.nonEmpty then
        val text = current.toString
        current.clear()
        closing match
          case ClosingProse.Withhold => withheld = Some(text)
          case ClosingProse.Render   => emit(text)

    /** Normal end of stream: the parked message IS the payload — drop it (the
      * caller emits StructuredResult); flush any unfinished current buffer.
      *
      * Since DecodedTurn closes every completed message, a normal turn ends its
      * last message with an `AssistantMessageEnd` that already ran
      * `messageEnd()`, leaving `current` empty. `flushCurrent()` is a safety
      * net for a message the stream left open (abnormal termination
      * mid-message).
      */
    def finishNormally(): Unit =
      withheld = None
      flushCurrent()

    /** The drain threw — reading the stream or answering an event: the message
      * boundary that would have told the payload from narration never arrived,
      * so flush everything rather than drop prose. Worst case the user sees a
      * JSON blob once.
      *
      * A failure the wire reports (an `is_error` result) closes the stream
      * normally, so [[finishNormally]] runs and the parked message is dropped
      * like any other closing message — an unfinished one still flushes from
      * `current`.
      */
    def finishAbnormally(): Unit =
      withheld.foreach(emit)
      withheld = None
      flushCurrent()

    private def flushCurrent(): Unit =
      if current.nonEmpty then
        emit(current.toString)
        current.clear()
