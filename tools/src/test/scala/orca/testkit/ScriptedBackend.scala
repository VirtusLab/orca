package orca.testkit

import orca.agents.{BackendTag, StructuredOutputMode, WireSessionId}
import orca.backend.{
  AgentBackend,
  AgentResult,
  Conversation,
  IdScheme,
  SessionSupport,
  TurnRequest
}
import orca.events.Usage
import ox.Ox

/** An `AgentBackend` double whose every turn, autonomous or interactive, is a
  * conversation with no events that answers [[reply]]. A `reply` that throws is
  * a turn that failed to open.
  *
  * `B` is bound to `Singleton` so `ScriptedBackend(BackendTag.Pi)` infers
  * `BackendTag.Pi.type` rather than widening to `BackendTag`.
  */
abstract class ScriptedBackend[B <: BackendTag & Singleton](
    val tag: B,
    val sessions: SessionSupport[B] =
      SessionSupport.ephemeral[B](IdScheme.ClientClaimed)
) extends AgentBackend[B]
    with StubEnforcementCell[B]:
  val workDir: os.Path = os.pwd
  def structuredOutputMode: StructuredOutputMode = StructuredOutputMode.RawText

  protected def reply(turn: TurnRequest[B]): AgentResult[B]

  override protected[orca] def open(turn: TurnRequest[B])(using
      Ox
  ): Conversation[B] =
    new ScriptedConversation(Nil, Right(reply(turn)), turn.outputSchema)

object ScriptedBackend:
  /** A result carrying `output`, reported under `wireId`. */
  def result[B <: BackendTag](
      output: String,
      wireId: String = "scripted-wire"
  ): AgentResult[B] =
    AgentResult(WireSessionId[B](wireId), output, Usage.empty)
