package orca.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import orca.agents.{
  BackendTag,
  JsonData,
  Model,
  StructuredOutputMode,
  WireSessionId
}
import orca.backend.{
  AgentBackend,
  AgentResult,
  LiveTurn,
  IdScheme,
  SessionSupport,
  TurnRequest
}
import orca.events.Usage
import ox.Ox

/** An `AgentBackend` double whose every turn, autonomous or interactive, is a
  * turn with no events that answers [[reply]]. A `reply` that throws is a turn
  * that failed to open. It has no cheaper model tier.
  *
  * `B` is bound to `Singleton` so `ScriptedBackend(BackendTag.Pi)` infers
  * `BackendTag.Pi.type` rather than widening to `BackendTag`.
  */
abstract class ScriptedBackend[B <: BackendTag & Singleton](
    val tag: B,
    sessionSupport: SessionSupport[B] =
      SessionSupport.ephemeral[B](IdScheme.ClientClaimed)
) extends AgentBackend[B]
    with StubEnforcementCell[B]:
  def sessions: SessionSupport[B] = sessionSupport
  val workDir: os.Path = os.pwd
  def structuredOutputMode: StructuredOutputMode = StructuredOutputMode.RawText
  def cheapModel(leading: Option[Model]): Option[Model] = None

  protected def reply(turn: TurnRequest[B]): AgentResult[B]

  override protected[orca] def open(turn: TurnRequest[B])(using
      Ox
  ): LiveTurn[B] =
    new ScriptedTurn(Nil, Right(reply(turn)), turn.outputSchema)

object ScriptedBackend:
  /** A backend answering every turn with `answer(turn)`'s output. */
  def replying[B <: BackendTag & Singleton](tag: B)(
      answer: TurnRequest[B] => String
  ): ScriptedBackend[B] =
    new ScriptedBackend[B](tag):
      protected def reply(turn: TurnRequest[B]): AgentResult[B] =
        result(answer(turn))

  /** A backend whose turns must not run: every one throws. */
  def unused[B <: BackendTag & Singleton](
      tag: B,
      sessions: SessionSupport[B] =
        SessionSupport.ephemeral[B](IdScheme.ClientClaimed)
  ): ScriptedBackend[B] =
    new ScriptedBackend[B](tag, sessions):
      protected def reply(turn: TurnRequest[B]): AgentResult[B] =
        throw new AssertionError("no turn expected")

  /** A result carrying `output`, reported under `wireId`. */
  def result[B <: BackendTag](
      output: String,
      wireId: String = "scripted-wire"
  ): AgentResult[B] =
    AgentResult(WireSessionId[B](wireId), output, Usage.empty)

  /** `value` as the JSON payload a structured (`resultAs[T]`) turn parses. */
  def json[T](value: T)(using jd: JsonData[T]): String =
    writeToString(value)(using jd.codec)
