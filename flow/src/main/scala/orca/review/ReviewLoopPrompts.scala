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
      |the issue is environmental (missing tooling, network), out of
      |scope for this task, or a false positive — list its title and a
      |brief reason under `ignored`. Every input issue should appear in
      |exactly one of the two lists.""".stripMargin

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
