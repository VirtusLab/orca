package orca.pr

import orca.util.PromptResource

/** Default prompt fragments for the helpers in this package, overridable via
  * each helper's `instructions` parameter. Source text lives in
  * `src/main/resources/orca/pr/prompts/`.
  */
object PrPrompts:

  /** Used by [[summarisePr]] to fold a diff (and an optional originating
    * context) into a [[PrSummary]].
    */
  val Summarise: String =
    PromptResource.load("/orca/pr/prompts/summarise.md")

  /** Appended to the summariser's instructions when the context is the run's
    * user prompt, the only case where the model decides which issues to close.
    */
  val ClosingRefs: String =
    PromptResource.load("/orca/pr/prompts/closing-refs.md")
