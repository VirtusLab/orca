package orca.backend

import orca.events.{Usage}
import orca.{BackendTag, SessionId}

/** Outcome of a single LLM call. Returned by [[LlmBackend.runHeadless]] /
  * [[LlmBackend.continueHeadless]] for the headless path, and by
  * [[Conversation.awaitResult]] / [[Interaction.drive]] for the interactive
  * path.
  */
case class LlmResult[B <: BackendTag](
    sessionId: SessionId[B],
    output: String,
    usage: Usage,
    /** Model the backend reports it actually served the call with — usually
      * present for headless calls (the CLI returns it), absent for some
      * conversation paths. Used as the bucket key in `CostTracker` so spend is
      * attributed to the concrete model rather than just the tool name.
      */
    model: Option[String] = None
)
