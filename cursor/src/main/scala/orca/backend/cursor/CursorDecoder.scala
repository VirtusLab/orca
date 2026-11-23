package orca.backend.cursor

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.backend.{
  AgentResult,
  AskUserChannel,
  LiveTurn,
  TurnEvent,
  DecodedTurnSpec,
  LineDecoder,
  Settled,
  Step,
  DecodedTurn,
  StreamSource
}
import orca.subprocess.PipedCliProcess
import orca.backend.cursor.jsonl.{InboundEvent, TerminalStatus}
import ox.Ox

/** Decodes a `cursor-agent -p --output-format stream-json` turn. */
private[cursor] object CursorDecoder
    extends LineDecoder[BackendTag.Cursor.type, CursorDecoder.State]:

  final case class State(
      sessionId: Option[WireSessionId[BackendTag.Cursor.type]],
      model: Option[String],
      answer: Vector[String]
  )

  private type Out = Step[BackendTag.Cursor.type, State]

  def backendName: String = "cursor"

  def terminalMessageNoun: String = "a result event"

  def init: State = State(None, None, Vector.empty)

  def line(state: State, line: String): Out =
    InboundEvent.parse(line) match
      case InboundEvent.Init(sessionId, model) =>
        Step.continue(
          state.copy(sessionId = Some(WireSessionId(sessionId)), model = model)
        )
      case InboundEvent.AssistantText(text) =>
        if text.isEmpty then Step.continue(state)
        else
          Step.continue(
            state.copy(answer = state.answer :+ text),
            TurnEvent.AssistantTextDelta(text)
          )
      case InboundEvent.UserEcho => Step.continue(state)
      case InboundEvent.Error(message) =>
        Step.continue(state, TurnEvent.Error(s"cursor-agent: $message"))
      case InboundEvent.Result(text, usage, status) =>
        result(state, text, usage, status)
      case InboundEvent.Unknown(_) => Step.continue(state)

  def failedTurnDebit(state: State): TurnDebit = TurnDebit.Unobserved

  private def result(
      state: State,
      terminalText: Option[String],
      usage: Usage,
      status: TerminalStatus
  ): Out =
    val debit = TurnDebit.Observed(usage, state.model.map(Model.apply))
    val output =
      if state.answer.nonEmpty then state.answer.mkString
      else terminalText.getOrElse("")
    val settled = (status, state.sessionId) match
      case (TerminalStatus.Success, Some(sessionId)) =>
        Settled.Succeeded(
          AgentResult(
            sessionId,
            output,
            usage,
            state.model.map(Model.apply)
          )
        )
      case (TerminalStatus.Success, None) =>
        Settled.Failed(
          "cursor-agent completed without a session id to resume",
          debit
        )
      case (TerminalStatus.Failure(raw), _) =>
        val description =
          if raw.isEmpty then "a non-success result" else s"result '$raw'"
        Settled.Failed(s"cursor-agent turn ended with $description", debit)
    Step.Settle(state, Nil, settled)

private[cursor] object CursorTurn:

  def apply(
      process: PipedCliProcess,
      openingPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: AskUserChannel = AskUserChannel.Unavailable
  )(using Ox): LiveTurn[BackendTag.Cursor.type] =
    DecodedTurn.start(
      StreamSource.fromProcess(process),
      DecodedTurnSpec(
        openingPrompt = openingPrompt,
        outputSchema = outputSchema,
        askUser = askUser
      ),
      CursorDecoder
    )