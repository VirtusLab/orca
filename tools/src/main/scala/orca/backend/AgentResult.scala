package orca.backend

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.{Usage}

/** Outcome of a single LLM call. Returned by [[AgentBackend.runAutonomous]] for
  * the autonomous path and by [[Conversation.awaitResult]] /
  * [[Interaction.drive]] for the interactive path.
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
    /** Model the backend reports it actually served the call with — usually
      * present for autonomous calls (the CLI returns it), absent for some
      * conversation paths. The bucket key in `CostTracker`, so spend is
      * attributed to the concrete model rather than the tool name.
      */
    model: Option[Model] = None
)
