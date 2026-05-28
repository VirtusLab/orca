package orca.pr

import orca.util.PromptResource

/** Default prompt fragment for the helpers in this package. Override by passing
  * a different string to the helper's `instructions` parameter — wrap the
  * default if you only want to extend it.
  *
  * Source text lives in `src/main/resources/orca/pr/prompts/`.
  */
object PrPrompts:

  /** Used by [[summarisePr]] to fold a diff (and an optional originating
    * context) into a [[PrSummary]].
    */
  val Summarise: String =
    PromptResource.load("/orca/pr/prompts/summarise.md")
