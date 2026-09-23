package orca.agents

import orca.backend.{AgentBackend, AgentResult}
import orca.events.{OrcaEvent, OrcaListener, TurnDebit, Usage}

/** Attributes one call's turns — which agent, which model, which role, which
  * session, which turn of the call — and emits the resulting events.
  *
  * Built once per call, so no emission site assembles the attribution itself
  * and a path that forgets the session key or the model fallback can't exist.
  *
  * @param sessionKey
  *   the key a durable `agent.session(name, seed)` minted this session under;
  *   `None` for one-shot and chat turns.
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
    sessionKey: Option[SessionKey],
    pinned: Option[Model]
):

  // Resolved per emission, not once at construction: for a server-minted id the
  // key only becomes the wire id once the turn commits it, and a turn must name
  // the same key `SessionCommitted` is deduplicated under.
  private def conversationKey: String =
    backend.sessions.conversationKey(session)

  /** `turn` is the turn's 1-based position among the turns of this call; a path
    * that never retries passes 1.
    */
  def succeeded(result: AgentResult[B], turn: Int): Unit =
    emit(result.model, result.usage, turn)

  /** A turn that failed after the model ran still spent tokens, and the success
    * path is the only other emitter. An `Unobserved` debit emits nothing.
    */
  def failedAfterModelRan(debit: TurnDebit, turn: Int): Unit = debit match
    case TurnDebit.Observed(usage, model) => emit(model, usage, turn)
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
        harness = backend.tag.wireName,
        clientId = session.value,
        wireId = backend.sessions.persistableWireId(session).map(_.value),
        sessionKey = sessionKey,
        agent = agentName,
        role = role
      )
    )

  private def emit(
      reported: Option[Model],
      usage: Usage,
      turn: Int
  ): Unit =
    events.onEvent(
      OrcaEvent.TokensUsed(
        agent = agentName,
        model = reported.orElse(pinned),
        usage = usage,
        role = role,
        turn = turn,
        session = Some(conversationKey),
        cost = None
      )
    )

private[orca] object TurnAccounting:
  /** The turn number of a call shape that runs one turn and never retries. */
  val OnlyTurn: Int = 1
