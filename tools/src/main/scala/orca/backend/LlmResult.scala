package orca.backend

import orca.llm.{BackendTag, Model, SessionId}
import orca.events.{Usage}

/** Outcome of a single LLM call. Returned by [[LlmBackend.runAutonomous]] /
  * [[LlmBackend.continueAutonomous]] for the autonomous path, and by
  * [[Conversation.awaitResult]] / [[Interaction.drive]] for the interactive
  * path.
  */
case class LlmResult[B <: BackendTag](
    sessionId: SessionId[B],
    output: String,
    usage: Usage,
    /** Model the backend reports it actually served the call with — usually
      * present for autonomous calls (the CLI returns it), absent for some
      * conversation paths. Used as the bucket key in `CostTracker` so spend is
      * attributed to the concrete model rather than just the tool name.
      */
    model: Option[Model] = None
)
