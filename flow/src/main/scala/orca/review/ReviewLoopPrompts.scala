package orca.review

import orca.util.PromptResource

/** Default prompt fragments for the helpers in this package. Each `val` is a
  * complete instruction block the helper sends as part of its LLM call;
  * override via the helper's `instructions` parameter, wrapping a default to
  * extend it:
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
    * input issue as `fixed` (title) or `ignored` (title + reason). The loop
    * relies on `fixed` being non-empty to justify re-evaluating, so any
    * override should preserve that contract.
    */
  val Fix: String =
    PromptResource.load("/orca/review/prompts/fix.md")

  /** Used by [[ReviewerSelector.agentDriven]] to decide which reviewers to run
    * for a given task. Agents are picked from the supplied `availableReviewers`
    * list by name.
    */
  val SelectReviewers: String =
    PromptResource.load("/orca/review/prompts/select-reviewers.md")

  /** Used by [[lint]] to fold a shell-lint's combined output into a
    * `ReviewResult`. Override when the lint produces unusual shapes the default
    * phrasing doesn't fit.
    */
  val SummariseLint: String =
    PromptResource.load("/orca/review/prompts/summarise-lint.md")

  private val InitialReviewTemplate: String =
    PromptResource.load("/orca/review/prompts/initial-review.md")

  /** Initial reviewer call: pin the agent to the supplied diff so it doesn't
    * fan out across the whole project. The same prompt template is used for
    * every reviewer; the reviewer's identity comes from its system prompt.
    *
    * `gate` is rendered into the prompt's confidence section, so reviewers are
    * told the actual bars their findings are measured against rather than a
    * hardcoded guess at them.
    */
  def initialReview(task: String, diff: String, gate: ConfidenceGate): String =
    PromptResource.render(
      InitialReviewTemplate,
      "task" -> task,
      "diffBlock" -> diffBlock(diff),
      "criticalBar" -> gate.critical.toString,
      "warningBar" -> gate.warning.toString,
      "infoBar" -> gate.info.toString
    )

  private val ReReviewTemplate: String =
    PromptResource.load("/orca/review/prompts/re-review.md")

  /** Continuation prompt for a reviewer's session on iterations after the
    * first. The session already holds the reviewer's earlier findings and the
    * diff it first saw; `diff` re-states the change set as it now stands, so
    * the fixer's edits are visible even when they were committed.
    */
  def reReview(diff: String): String =
    PromptResource.render(ReReviewTemplate, "diffBlock" -> diffBlock(diff))

  /** The diff as a fenced block, or a note to fall back to the working tree
    * when nothing was captured.
    */
  private def diffBlock(diff: String): String =
    if diff.trim.isEmpty then "(no diff captured — review the working tree)"
    else s"```diff\n$diff\n```"
