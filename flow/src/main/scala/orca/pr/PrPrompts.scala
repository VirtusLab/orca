package orca.pr

import orca.util.PromptResource

/** Prompt fragments for the helpers in this package; `Summarise` is overridable
  * via `instructions`. Source text lives in
  * `src/main/resources/orca/pr/prompts/`.
  */
object PrPrompts:

  /** Used by [[summarisePr]] to fold a diff (and an optional originating
    * context) into a [[PrSummary]].
    */
  val Summarise: String =
    PromptResource.load("/orca/pr/prompts/summarise.md")

  /** Appended to the summariser's instructions, custom ones included, when
    * `context` is omitted and the run's user prompt stands in for it.
    */
  val ClosingRefs: String =
    PromptResource.load("/orca/pr/prompts/closing-refs.md")
