package orca.review

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
  *   reviewers = defaultReviewers(claude),
  *   task = title,
  *   fixInstructions = ReviewLoopPrompts.Fix +
  *     "\n\nIf you delete a test, mention it in the ignored reason."
  * )
  * }}}
  */
object ReviewLoopPrompts:

  /** Used by [[reviewAndFixLoop]]'s fix step. Tells the agent to classify every
    * input issue as either `fixed` (title listed) or `ignored` (title +
    * reason). The loop relies on `fixed` being non-empty to justify
    * re-evaluating, so any override should preserve that contract.
    */
  val Fix: String =
    """For each review comment below: fix it directly in the codebase
      |if you can, then list its title under `fixed`. Otherwise — when
      |the issue is environmental, out of scope, or a false positive —
      |list its title and a brief reason under `ignored`. Every input
      |issue should appear in exactly one of the two lists.
      |
      |Prefer minimal, scoped fixes.""".stripMargin

  /** Used by [[ReviewerSelector.llmDriven]] to decide which reviewers to run
    * for a given task. Agents are picked from the supplied `availableReviewers`
    * list by name.
    */
  val SelectReviewers: String =
    """Pick the subset of `availableReviewers` whose dimension is most
      |relevant to this task — judging by the title and the changed
      |files. Skip reviewers whose dimension clearly doesn't apply (e.g.
      |a test-coverage reviewer when no production code changed). Reply
      |with a SelectedReviewers containing only names from
      |`availableReviewers`.""".stripMargin

  /** Used by [[lint]] to fold a shell-lint's combined output into a
    * `ReviewResult`. Override when the lint produces unusual shapes the default
    * phrasing doesn't fit.
    */
  val SummarizeLint: String =
    """Summarize the following lint output into a ReviewResult. Each
      |distinct issue should produce a ReviewIssue; use reasonable
      |confidence based on how actionable the message is.""".stripMargin

  /** Initial reviewer call: pin the agent to the supplied diff so it doesn't
    * fan out across the whole project. The same prompt template is used for
    * every reviewer; the reviewer's identity comes from its system prompt.
    */
  def initialReview(task: String, diff: String): String =
    val diffBlock =
      if diff.trim.isEmpty then "(no diff captured — review the working tree)"
      else s"```diff\n$diff\n```"
    s"""Task: $task
       |
       |Review the following changes only — do NOT survey unrelated
       |files in the project. Focus your findings strictly on what the
       |diff modifies and on code that interacts directly with it.
       |
       |Diff (working tree vs HEAD at the start of the review loop):
       |
       |$diffBlock
       |
       |Output a ReviewResult.""".stripMargin

  /** Continuation prompt for a reviewer's session on iterations after the
    * first. The session already contains the original diff and the reviewer's
    * earlier findings; the working tree may have changed in response to a fix.
    */
  val ReReview: String =
    """Fixes have been applied to the working tree based on your earlier
      |review. Re-review the current state — focus on whether your
      |earlier findings were addressed and on any new issues introduced
      |by the fix. Stay scoped to the same changes you reviewed
      |initially; do not expand to unrelated files. Output a
      |ReviewResult.""".stripMargin
