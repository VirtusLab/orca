package orca.agents

import orca.backend.{AgentBackend, AgentResult}
import orca.events.{OrcaEvent, OrcaListener, TurnDebit, Usage}

/** Attributes one call's turns — which agent, which model, which role, which
  * session, which attempt — and emits the resulting events.
  *
  * Built once per call, so no emission site assembles the attribution itself
  * and a path that forgets the session key or the model fallback can't exist.
  *
  * @param pinned
  *   the model the caller configured, used wherever the turn itself reports
  *   none.
  */
private[orca] class TurnAccounting[B <: BackendTag](
    events: OrcaListener,
    agentName: String,
    role: Option[String],
    backend: AgentBackend[B],
    session: SessionId[B],
    pinned: Option[Model]
):

  // Resolved per emission, not once at construction: for a server-minted id the
  // key only becomes the wire id once the turn commits it, and a turn must name
  // the same key `SessionCommitted` is deduplicated under.
  private def sessionKey: String = backend.sessions.sessionKey(session)

  /** `attempt` is the turn's 1-based position among the turns of this call; a
    * path that never retries passes 1.
    */
  def succeeded(result: AgentResult[B], attempt: Int): Unit =
    emit(result.model, result.usage, attempt)

  /** A turn that failed after the model ran still spent tokens, and the success
    * path is the only other emitter. An `Unobserved` debit emits nothing.
    */
  def failedAfterModelRan(debit: TurnDebit, attempt: Int): Unit = debit match
    case TurnDebit.Observed(usage, model) => emit(model, usage, attempt)
    case TurnDebit.Unobserved             => ()

  /** Run `turn`, recording the debit of a turn that ended after the model ran —
    * failed or cancelled by the user — then re-raising it. For the call shapes
    * that run one turn and never retry, hence [[TurnAccounting.OnlyTurn]].
    */
  def recording(turn: => AgentResult[B]): AgentResult[B] =
    try turn
    catch
      case e: orca.AgentTurnFailed =>
        failedAfterModelRan(e.debit, TurnAccounting.OnlyTurn)
        throw e
      case e: orca.OrcaInteractiveCancelled =>
        failedAfterModelRan(e.debit, TurnAccounting.OnlyTurn)
        throw e

  /** Fires once a session's first turn commits (ADR 0021 §8). Call after the
    * backend drain returns, so the wire id reflects what that turn committed.
    */
  def sessionCommitted(): Unit =
    events.onEvent(
      OrcaEvent.SessionCommitted(
        backend.tag.wireName,
        session.value,
        backend.sessions.persistableWireId(session).map(_.value),
        agentName,
        role
      )
    )

  private def emit(
      reported: Option[Model],
      usage: Usage,
      attempt: Int
  ): Unit =
    events.onEvent(
      OrcaEvent.TokensUsed(
        agent = agentName,
        model = reported.orElse(pinned),
        usage = usage,
        role = role,
        attempt = attempt,
        session = Some(sessionKey)
      )
    )

private[orca] object TurnAccounting:
  /** The attempt number of a call shape that runs one turn and never retries.
    */
  val OnlyTurn: Int = 1
