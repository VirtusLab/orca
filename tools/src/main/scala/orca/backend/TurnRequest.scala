package orca.backend

import orca.agents.{AgentConfig, BackendTag, SessionId}
import orca.events.OrcaListener

/** Everything a backend needs to open one turn's [[LiveTurn]] — see
  * [[AgentBackend.open]].
  *
  * @param prompt
  *   the full wire-level message, with template scaffolding, schema and rules
  *   already wrapped around the user's input.
  * @param dispatch
  *   this turn's fresh-vs-resume answer for `session`.
  * @param outputSchema
  *   the JSON Schema the final reply must conform to, or `None` for free-form
  *   text.
  * @param events
  *   the turn's listener, for what the backend reports outside the turn's own
  *   events (the environment-cookie sweep).
  */
private[orca] final case class TurnRequest[B <: BackendTag](
    prompt: String,
    session: SessionId[B],
    dispatch: Dispatch[B],
    mode: TurnMode,
    config: AgentConfig,
    outputSchema: Option[String],
    events: OrcaListener
)
