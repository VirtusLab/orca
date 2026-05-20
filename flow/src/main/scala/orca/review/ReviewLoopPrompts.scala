package orca.review

import orca.util.PromptResource

/** Default prompt fragments for the helpers in this package. Each `val` is a
  * complete instruction block that the helper sends as part of its LLM call.
  * Override by passing a different string to the helper's `instructions`
  * parameter — wrap one of these defaults if you only want to extend the
  * boilerplate:
  *
  * {{{
  * reviewAndFixLoop(
  *   coder = claude,
  *   sessionId = sessionId,
  *   reviewers = allReviewers(claude),
  *   task = title,
  *   fixInstructions = ReviewLoopPrompts.Fix +
  *     "\n\nIf you delete a test, mention it in the ignored reason."
  * )
  * }}}
  *
  * Source text lives in `src/main/resources/orca/review/prompts/`.
  */
object ReviewLoopPrompts:

  /** Used by [[reviewAndFixLoop]]'s fix step. Tells the agent to classify every
    * input issue as either `fixed` (title listed) or `ignored` (title +
    * reason). The loop relies on `fixed` being non-empty to justify
    * re-evaluating, so any override should preserve that contract.
    */
  val Fix: String =
    PromptResource.load("/orca/review/prompts/fix.md")

  /** Used by [[ReviewerSelector.llmDriven]] to decide which reviewers to run
    * for a given task. Agents are picked from the supplied `availableReviewers`
    * list by name.
    */
  val SelectReviewers: String =
    PromptResource.load("/orca/review/prompts/select-reviewers.md")

  /** Used by [[lint]] to fold a shell-lint's combined output into a
    * `ReviewResult`. Override when the lint produces unusual shapes the default
    * phrasing doesn't fit.
    */
  val SummarizeLint: String =
    PromptResource.load("/orca/review/prompts/summarize-lint.md")

  private val InitialReviewTemplate: String =
    PromptResource.load("/orca/review/prompts/initial-review.md")

  /** Initial reviewer call: pin the agent to the supplied diff so it doesn't
    * fan out across the whole project. The same prompt template is used for
    * every reviewer; the reviewer's identity comes from its system prompt.
    */
  def initialReview(task: String, diff: String): String =
    val diffBlock =
      if diff.trim.isEmpty then "(no diff captured — review the working tree)"
      else s"```diff\n$diff\n```"
    PromptResource.render(
      InitialReviewTemplate,
      "task" -> task,
      "diffBlock" -> diffBlock
    )

  /** Continuation prompt for a reviewer's session on iterations after the
    * first. The session already contains the original diff and the reviewer's
    * earlier findings; the working tree may have changed in response to a fix.
    */
  val ReReview: String =
    PromptResource.load("/orca/review/prompts/re-review.md")
