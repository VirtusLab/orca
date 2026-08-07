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
    *
    * `base` is the commit `diff` was sampled against, when the loop knows it
    * describes this diff (see [[ReviewFixLoop.diffBase]]). It is sent alongside
    * the diff, never instead of it: it only lets a reviewer read the repo at
    * that commit, and a reviewer with no way to do so is unaffected.
    */
  def initialReview(
      task: String,
      diff: String,
      gate: ConfidenceGate,
      base: Option[String]
  ): String =
    PromptResource.render(
      InitialReviewTemplate,
      "task" -> task,
      "diffBlock" -> diffBlock(diff),
      "baseNote" -> baseNote(base),
      "criticalBar" -> gate.critical.toString,
      "warningBar" -> gate.warning.toString,
      "infoBar" -> gate.info.toString
    )

  /** The base commit as a paragraph after the diff, carrying its own leading
    * blank line so the section disappears without a trace when there is no base
    * — the template writes `{{diffBlock}}{{baseNote}}` with no separator of its
    * own.
    */
  private def baseNote(base: Option[String]): String =
    base.fold(""): sha =>
      s"\n\nThe diff above is everything that changed since commit $sha. To " +
        "see what the diff doesn't show, read a file as it was before the " +
        s"change: `git_file_at` at that commit, or `git show $sha:<path>` if " +
        "you have a shell. What you review is still the diff; the base " +
        "commit is there for evidence, not for widening your scope."

  private val ReReviewTemplate: String =
    PromptResource.load("/orca/review/prompts/re-review.md")

  /** Continuation prompt for a reviewer's session on iterations after the
    * first. The session already holds the reviewer's earlier findings and every
    * change set it has been sent, so `changes` carries only what is new to it —
    * including the base commit, which the initial prompt named and this one
    * therefore doesn't repeat.
    *
    * `declined` is what the fixer refused to fix last round, which is the one
    * thing a reviewer cannot recover by reading the code.
    */
  private[review] def reReview(
      changes: ReReviewChanges,
      declined: List[IgnoredIssue]
  ): String =
    PromptResource.render(
      ReReviewTemplate,
      "changes" -> changesBlock(changes),
      "declined" -> declinedBlock(declined)
    )

  /** The fixer's declines as a paragraph after the change set, carrying its own
    * leading blank line for the same reason as [[baseNote]].
    *
    * Worded as the fixer's position rather than a verdict on the finding. A
    * reviewer told "this was settled" would stop checking, which is the failure
    * this block exists to avoid — the point is to save a round on findings the
    * fixer has already answered, not to withdraw them.
    */
  private def declinedBlock(declined: List[IgnoredIssue]): String =
    if declined.isEmpty then ""
    else
      "\n\nThe fixer declined to fix these findings, and gave this reason " +
        s"for each:\n\n${IgnoredIssues(declined).format}\n\nThat is the " +
        "fixer's position, not a ruling. If you still think a finding is " +
        "real, report it again and say why the reason is wrong."

  private def changesBlock(changes: ReReviewChanges): String =
    changes match
      case ReReviewChanges.Updated(diff) =>
        "Diff (the change set under review, re-sampled from the same baseline " +
          "as your initial diff, so it includes the fixer's edits whether or " +
          "not they were committed). Do not use `git diff HEAD` instead — it " +
          s"does not show work that has been committed:\n\n${diffBlock(diff)}"
      case ReReviewChanges.TooLarge(paths) =>
        "The change set under review is too large to include here. These " +
          "files have changed since the baseline of your initial diff — read " +
          "them directly. Do not use `git diff HEAD` instead — it does not " +
          s"show work that has been committed:\n\n" +
          paths.map("- " + _).mkString("\n")
      case ReReviewChanges.AlreadySeen =>
        "No new change set this round — the diff already in this conversation " +
          "is the one under review. Check the code itself to see whether your " +
          "earlier findings still stand."

  /** The diff as a fenced block, or a note when nothing could be sampled. An
    * empty sample means the loop couldn't describe the change, not that none
    * was made (ADR 0011), so the note has to say so.
    */
  private def diffBlock(diff: String): String =
    if diff.trim.isEmpty then
      "(no change set could be sampled — do not conclude that nothing " +
        "changed; inspect the code the task describes)"
    else s"```diff\n$diff\n```"

/** What a resumed reviewer is told about the change set this round.
  *
  * A resumed reviewer already holds every change set it has been sent. Sending
  * it the same one again, under text saying it was freshly re-sampled, would
  * claim the fixer's edits are inside a diff that predates them, and the
  * reviewer would re-report findings that were already fixed. A pinned
  * `initialDiff` produces exactly that repeat.
  */
private[review] enum ReReviewChanges:
  /** Re-sampled, and different from what this reviewer last saw. */
  case Updated(diff: String)

  /** Changed, but past [[ReReviewChanges.InlineThreshold]]. Only the paths are
    * sent and the reviewer reads the files itself, so a resumed conversation
    * doesn't accumulate one copy of a large diff per round.
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

  /** Classify this round's sample against what the reviewer last received. The
    * [[DiffSample]] carries the paths alongside the diff they describe, so
    * [[TooLarge]] can never name a different change set than the one it stands
    * in for.
    *
    * Equality is tested before size, so a pinned `initialDiff` never reaches
    * [[TooLarge]]: pinned samples are byte-identical every round, so a resume
    * always classifies [[AlreadySeen]].
    */
  def of(previous: String, current: DiffSample): ReReviewChanges =
    if current.diff == previous then AlreadySeen
    else if current.diff.length > InlineThreshold then TooLarge(current.paths)
    else Updated(current.diff)
