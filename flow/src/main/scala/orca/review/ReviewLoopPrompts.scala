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
    * first. The session already holds the reviewer's earlier findings and every
    * change set it has been sent, so `changes` carries only what is new to it.
    */
  private[review] def reReview(changes: ReReviewChanges): String =
    PromptResource.render(ReReviewTemplate, "changes" -> changesBlock(changes))

  private def changesBlock(changes: ReReviewChanges): String =
    changes match
      case ReReviewChanges.Updated(diff) =>
        "Diff (the change set under review, re-sampled from the same baseline " +
          "as your initial diff — it includes the fixer's edits whether or not " +
          "it committed them). `git diff HEAD` is not this change set; it does " +
          s"not show work already committed:\n\n${diffBlock(diff)}"
      case ReReviewChanges.TooLarge(paths) =>
        "The change set under review is too large to include here. These files " +
          "have changed since the baseline of your initial diff — read them " +
          "directly, and note that `git diff HEAD` does not show work already " +
          s"committed:\n\n${paths.map("- " + _).mkString("\n")}"
      case ReReviewChanges.AlreadySeen =>
        "No updated change set is available this round — the diff already in " +
          "this conversation is the one under review. Verify against the code " +
          "itself whether your earlier findings still stand."

  /** The diff as a fenced block, or — when nothing could be sampled — a note
    * that says so without implying the change set is empty. An empty sample
    * means the loop could not describe the change, not that none was made (ADR
    * 0011).
    */
  private def diffBlock(diff: String): String =
    if diff.trim.isEmpty then
      "(no change set could be sampled — do not conclude that nothing " +
        "changed; inspect the code the task describes)"
    else s"```diff\n$diff\n```"

/** What a resumed reviewer is told about the change set this round.
  *
  * The cases exist because a resumed reviewer already holds every change set it
  * has been sent. Re-sending one it has — which is what a pinned `initialDiff`
  * produces every round — under text asserting it was re-sampled would tell the
  * reviewer the fixer's edits are inside a diff that predates them, and it
  * would re-report findings that were already addressed.
  */
private[review] enum ReReviewChanges:
  /** Re-sampled, and different from what this reviewer last saw. */
  case Updated(diff: String)

  /** Re-sampled and changed, but past [[ReReviewChanges.InlineThreshold]]. Only
    * the paths are sent; the reviewer reads the files itself. Bounds what one
    * resumed conversation accumulates: the diff is re-sent every round, so an
    * uncapped payload would grow with the round count on exactly the largest
    * changes.
    */
  case TooLarge(paths: List[String])

  /** Byte-identical to what this reviewer already holds, so nothing is sent. */
  case AlreadySeen

private[review] object ReReviewChanges:
  /** Max diff length (chars) inlined into a re-review prompt. Above it the
    * reviewer gets paths and opens the files, which costs fewer tokens than the
    * hunks once a change is this large. Bigger than
    * [[Lint.InlineLintThreshold]] because the diff is the reviewer's primary
    * evidence, not tool output.
    */
  private[review] val InlineThreshold: Int = 16 * 1024

  /** Classify this round's sample against what the reviewer last received.
    * `paths` is used only past the threshold, and the caller samples it from
    * git beside the diff — see `ReviewFixLoop.runReviewersAndLint`.
    *
    * Equality is tested before size, which is what makes a pinned `initialDiff`
    * unable to reach [[TooLarge]]: pinned samples are byte-identical every
    * round, so a resume always classifies [[AlreadySeen]].
    */
  def of(
      previous: String,
      current: String,
      paths: List[String]
  ): ReReviewChanges =
    if current == previous then AlreadySeen
    else if current.length > InlineThreshold then TooLarge(paths)
    else Updated(current)
