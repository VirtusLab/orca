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
private[cursor] final class CursorDecoder(workDir: os.Path)
    extends LineDecoder[BackendTag.Cursor.type, CursorDecoder.State]:

  import CursorDecoder.State

  private type Out = Step[BackendTag.Cursor.type, State]

  def backendName: String = "cursor"

  def terminalMessageNoun: String = "a result event"

  def init: State = State(None, None, Vector.empty, None)

  def line(state: State, line: String): Out =
    if !line.trim.startsWith("{") && CursorWorkspaceTrust.required(line) then
      Step.Settle(
        state,
        Nil,
        Settled
          .Failed(CursorWorkspaceTrust.guidance(workDir), TurnDebit.Unobserved)
      )
    else decode(state, InboundEvent.parse(line))

  private def decode(state: State, event: InboundEvent): Out =
    event match
      case InboundEvent.Init(sessionId, model) =>
        Step.continue(
          state.copy(sessionId = Some(WireSessionId(sessionId)), model = model)
        )
      case InboundEvent.AssistantText(text) =>
        if text.isEmpty then Step.continue(state)
        else
          Step.continue(
            state.copy(answer = state.answer :+ text),
            TurnEvent.AssistantTextDelta(text),
            TurnEvent.AssistantMessageEnd
          )
      case InboundEvent.ToolStarted(name, payload) =>
        Step.continue(
          state,
          TurnEvent.AssistantToolCall(toolName = name, rawInput = payload),
          TurnEvent.AssistantMessageEnd
        )
      case InboundEvent.ToolCompleted(name, ok, payload) =>
        Step.continue(
          state,
          TurnEvent.ToolResult(name, ok, payload),
          TurnEvent.AssistantMessageEnd
        )
      case InboundEvent.UserEcho => Step.continue(state)
      case InboundEvent.Error(message) =>
        if CursorWorkspaceTrust.required(message) then
          Step.Settle(
            state,
            Nil,
            Settled.Failed(
              CursorWorkspaceTrust.guidance(workDir),
              TurnDebit.Unobserved
            )
          )
        else
          Step.continue(
            state.copy(error = Some(message)),
            TurnEvent.Error(s"cursor-agent: $message")
          )
      case InboundEvent.Result(text, usage, status) =>
        result(state, text, usage, status)
      case InboundEvent.Unknown(_) => Step.continue(state)

  override def stderrDiagnostic(line: String): String =
    CursorWorkspaceTrust.explain(line, workDir)

  override def isShutdownStderrNoise(line: String): Boolean =
    line == "Aborting operation..."

  override def protocolContext(state: State): Option[String] = state.error

  def failedTurnDebit(state: State): TurnDebit = TurnDebit.Unobserved

  private def result(
      state: State,
      terminalText: Option[String],
      usage: Option[Usage],
      status: TerminalStatus
  ): Out =
    val debit = usage.fold[TurnDebit](TurnDebit.Unobserved)(u =>
      TurnDebit.Observed(u, state.model.map(Model.apply))
    )
    val output =
      if state.answer.nonEmpty then state.answer.mkString
      else terminalText.getOrElse("")
    val settled = (status, state.sessionId) match
      case (TerminalStatus.Success, Some(sessionId)) =>
        Settled.Succeeded(
          AgentResult(
            sessionId,
            output,
            usage.getOrElse(Usage.empty),
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
        val detail =
          terminalText.filter(_.nonEmpty).fold("")(text => s": $text")
        Settled.Failed(
          CursorWorkspaceTrust.explain(
            s"cursor-agent turn ended with $description$detail",
            workDir
          ),
          debit
        )
    Step.Settle(state, Nil, settled)

private[cursor] object CursorDecoder:
  final case class State(
      sessionId: Option[WireSessionId[BackendTag.Cursor.type]],
      model: Option[String],
      answer: Vector[String],
      error: Option[String]
  )

private[cursor] object CursorTurn:

  def apply(
      process: PipedCliProcess,
      workDir: os.Path = os.pwd,
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
      new CursorDecoder(workDir)
    )
