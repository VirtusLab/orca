package orca.backend

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.{Usage}

/** Outcome of a single LLM call. Returned by [[AgentBackend.runAutonomous]] /
  * [[AgentBackend.continueAutonomous]] for the autonomous path, and by
  * [[Conversation.awaitResult]] / [[Interaction.drive]] for the interactive
  * path.
  */
case class AgentResult[B <: BackendTag](
    /** The session id the backend reported for this turn — the WIRE id (server
      * thread id for codex/gemini/opencode; the claimed client id for
      * claude/pi). Callers wanting the stable client handle already hold it —
      * they passed it in; this field exists so the registry can learn the
      * mapping.
      */
    wireId: WireSessionId[B],
    output: String,
    usage: Usage,
    /** Model the backend reports it actually served the call with — usually
      * present for autonomous calls (the CLI returns it), absent for some
      * conversation paths. Used as the bucket key in `CostTracker` so spend is
      * attributed to the concrete model rather than just the tool name.
      */
    model: Option[Model] = None
)
