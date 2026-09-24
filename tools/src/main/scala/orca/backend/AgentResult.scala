package orca.backend

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.{Usage}

/** Outcome of a single LLM call. Returned by [[AgentBackend.runAutonomous]] for
  * the autonomous path and by [[LiveTurn.awaitResult]] / [[Interaction.drive]]
  * for the interactive path.
  */
case class AgentResult[B <: BackendTag](
    /** The WIRE session id the backend reported for this turn (server thread id
      * for codex/gemini/opencode; the claimed client id for claude/pi). Exists
      * so the registry can learn the wire↔client mapping — callers already hold
      * the stable client handle they passed in.
      */
    wireId: WireSessionId[B],
    output: String,
    usage: Usage,
    /** The model the backend attributes the turn to: the one the turn's own
      * output names, else a backend-specific fallback (codex: its configured
      * model); `None` when neither exists. Spend is attributed to it, falling
      * back to the call's pinned model.
      */
    model: Option[Model] = None
)
