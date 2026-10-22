package orca.backend

import orca.agents.BackendTag
import orca.events.{OrcaEvent, OrcaListener}
import orca.safe.prelude.orThrow

/** A [[LiveTurn]] whose display events go to an [[orca.events.OrcaListener]],
  * on both the autonomous and the interactive path, leaving the consumer only
  * the [[ChannelEvent]]s it must answer.
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
    var buffer = MessageBuffer.empty(closingProse(live))
    def emit(texts: List[String]): Unit =
      texts.foreach(text => listener.onEvent(OrcaEvent.AssistantMessage(text)))
    def step(next: (MessageBuffer, List[String])): Unit =
      buffer = next._1
      emit(next._2)
    try
      live.events.foreach: event =>
        if event.opensMessage then step(buffer.onActivity)
        event match
          case TurnEvent.AssistantToolCall(name, raw) =>
            listener.onEvent(OrcaEvent.ToolUse(name, raw))
          case TurnEvent.AssistantTextDelta(delta) =>
            buffer = buffer.append(delta)
          case TurnEvent.AssistantThinkingDelta(_) => ()
          case TurnEvent.AssistantMessageEnd       => step(buffer.messageEnd)
          case TurnEvent.ToolResult(_, _, _)       => ()
          case TurnEvent.ToolDenied(toolName)      =>
            listener.onEvent(OrcaEvent.ToolDenied(toolName, None))
          case TurnEvent.Error(message) =>
            listener.onEvent(OrcaEvent.Error(message))
          case TurnEvent.UserMessage(text) =>
            listener.onEvent(OrcaEvent.UserPrompt(text))
          case TurnEvent.Approval(request) => answer(request)
          case TurnEvent.Question(request) => answer(request)
      emit(buffer.finishNormally)
    catch
      case t: Throwable =>
        emit(buffer.finishAbnormally)
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

  /** The prose of the assistant message in progress, and a completed one held
    * back: each transition returns the messages to render as one
    * `AssistantMessage` each, holding back the CLOSING message when it carries
    * the structured payload.
    *
    * A message can only be recognised as closing after the fact, so a completed
    * message is parked in `withheld` and released by [[onActivity]] — the first
    * assistant event of the next message — which keeps a message's narration
    * ahead of the tool calls it announces. What survives to end-of-stream is
    * the closing message, and only that is dropped. A [[ChannelEvent]] opens no
    * message, so it is never stuck behind a withheld one.
    */
  private final case class MessageBuffer(
      closing: ClosingProse,
      current: Vector[String],
      withheld: Option[String]
  ):
    /** Assistant activity opening a message (`TurnEvent.opensMessage`): the
      * parked message isn't the closing one after all, so release it. Apply
      * before rendering the triggering event, so a message's narration lands
      * ahead of the tool call it announces.
      */
    def onActivity: (MessageBuffer, List[String]) =
      (copy(withheld = None), withheld.toList)

    def append(delta: String): MessageBuffer = copy(current = current :+ delta)

    def messageEnd: (MessageBuffer, List[String]) =
      currentText match
        case None       => (this, Nil)
        case Some(text) =>
          val cleared = copy(current = Vector.empty)
          closing match
            case ClosingProse.Withhold =>
              (cleared.copy(withheld = Some(text)), Nil)
            case ClosingProse.Render => (cleared, List(text))

    /** Normal end of stream: the parked message IS the payload — drop it (the
      * caller emits StructuredResult); flush any unfinished current message.
      *
      * Since DecodedTurn closes every completed message, a normal turn ends its
      * last message with an `AssistantMessageEnd` that already ran
      * [[messageEnd]], leaving `current` empty. The flush is a safety net for a
      * message the stream left open (abnormal termination mid-message).
      */
    def finishNormally: List[String] = currentText.toList

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
    def finishAbnormally: List[String] = withheld.toList ++ currentText

    private def currentText: Option[String] =
      Some(current.mkString).filter(_.nonEmpty)

  private object MessageBuffer:
    def empty(closing: ClosingProse): MessageBuffer =
      MessageBuffer(closing, Vector.empty, None)
