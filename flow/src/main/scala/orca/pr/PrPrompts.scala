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
