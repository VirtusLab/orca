package orca.review

import orca.BoundedDiff
import orca.plan.Task
import orca.util.PromptResource

/** Default prompt fragments for the helpers in this package. Each `val` is a
  * complete instruction block the helper sends as part of its LLM call;
  * override via the helper's `instructions` parameter, wrapping a default to
  * extend it:
  *
  * {{{
  * reviewAndFixLoop(
  *   coderSession = coderSession,
  *   reviewers = allReviewers(claude),
  *   task = task,
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

  /** The always-report categories, worded once. Substituted into both review
    * templates at init; [[declinedBlock]] back-references the copy those
    * templates render below it.
    */
  private[review] val MandatoryCategories: String =
    "user data loss, silent inversion of what the user asked for, or a " +
      "blocked or hung process"

  private val InitialReviewTemplate: String =
    PromptResource
      .load("/orca/review/prompts/initial-review.md")
      .replace("{{mandatoryCategories}}", MandatoryCategories)

  /** Initial reviewer call: pin the agent to the supplied diff so it doesn't
    * fan out across the whole project. The same prompt template is used for
    * every reviewer; the reviewer's identity comes from its system prompt.
    *
    * `task` and `userRequest` render as separately labelled sections under the
    * task title.
    *
    * `diffIntro` introduces the diff, and `base` names the commit `diff` was
    * sampled against when the loop knows that describes this diff. The base is
    * sent alongside the diff, never instead of it: it only lets a reviewer read
    * the repo at that commit, and a reviewer with no way to do so is
    * unaffected.
    *
    * `declined` matters for a reviewer first activated after round one — see
    * [[reviewAndFixLoop]].
    */
  def initialReview(
      task: Task,
      userRequest: String,
      diff: String,
      diffIntro: String,
      base: Option[String],
      declined: List[IgnoredIssue]
  ): String =
    PromptResource.render(
      InitialReviewTemplate,
      "taskTitle" -> task.title.value,
      "taskContext" -> taskContext(task, userRequest),
      "diffIntro" -> diffIntro,
      "diffBlock" -> diffBlock(diff),
      "baseNote" -> baseNote(base),
      "declined" -> declinedBlock(declined)
    )

  /** The task's context as labelled sections under the title: what the user
    * asked for, then the planner's description of this task. Both are short
    * prose, so they go in whole.
    *
    * Each section carries its own leading blank line, as [[baseNote]] does. A
    * section that is blank, or that repeats the title, is dropped — a flow with
    * no planning stage has neither to add.
    */
  private def taskContext(task: Task, userRequest: String): String =
    val title = task.title.value.trim
    List(
      "The user's request for this run" -> userRequest.trim,
      "The planner's description of this task" -> task.description.trim
    ).collect:
      case (label, text) if text.nonEmpty && text != title =>
        s"\n\n$label:\n\n$text"
    .mkString

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
    PromptResource
      .load("/orca/review/prompts/re-review.md")
      .replace("{{mandatoryCategories}}", MandatoryCategories)

  /** Continuation prompt for a reviewer's session on iterations after the
    * first. The session already holds the reviewer's earlier findings and every
    * change set it has been sent, so `changes` carries only what is new to it —
    * including the base commit, which the initial prompt named and this one
    * therefore doesn't repeat.
    *
    * `declined` is every refusal the fixer has made and not since fixed — see
    * [[reviewAndFixLoop]].
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
        "real, report it again and say why the reason is wrong. \"The plan " +
        "chose this\" is not on its own a sufficient answer for a finding " +
        "in the always-report categories below — re-report such a finding."

  private def changesBlock(changes: ReReviewChanges): String =
    changes match
      case ReReviewChanges.Updated(diff) =>
        "Diff (the change set under review, re-sampled from the same baseline " +
          "as your initial diff, so it includes the fixer's edits whether or " +
          "not they were committed). Do not use `git diff HEAD` instead — it " +
          s"does not show work that has been committed:\n\n${diffBlock(diff)}"
      case ReReviewChanges.TooLarge(None, changed, _) =>
        "The change set under review is too large to include here. These " +
          "files have changed since the baseline of your initial diff — read " +
          "them directly. Do not use `git diff HEAD` instead — it does not " +
          s"show work that has been committed:\n\n" +
          pathList(changed)
      case ReReviewChanges.TooLarge(Some(sections), _, Nil) =>
        "The change set under review is too large to include whole. Below is " +
          "as much of it as fits; any file it does not show is named after " +
          "it. Do not use `git diff HEAD` instead — it does not show work " +
          s"that has been committed:\n\n${diffBlock(sections)}"
      case ReReviewChanges.TooLarge(Some(sections), _, unchanged) =>
        "The change set under review is too large to include whole. Below is " +
          "the part of it that changed since your previous round; any file " +
          "that part does not show is named after it. Do not use `git diff " +
          "HEAD` instead — it does not show work that has been " +
          s"committed:\n\n${diffBlock(sections)}\n\nThe rest of the change " +
          "set is unchanged since your previous round — you need not re-read " +
          s"it:\n\n${pathList(unchanged)}"
      case ReReviewChanges.AlreadySeen(LastSent.Inline(_)) =>
        "No new change set this round — the diff already in this conversation " +
          "is the one under review. Check the code itself to see whether your " +
          "earlier findings still stand."
      case ReReviewChanges.AlreadySeen(LastSent.SectionsOnly(_)) =>
        "No new change set this round — the diff sections already in this " +
          "conversation still describe the change set. Check the code itself " +
          "to see whether your earlier findings still stand."
      case ReReviewChanges.AlreadySeen(LastSent.PathsOnly(_)) =>
        "No new change set this round — the file list already in this " +
          "conversation still describes the change set. Re-read those files " +
          "to see whether your earlier findings still stand."
      case ReReviewChanges.AlreadySeen(LastSent.NoteOnly(_)) =>
        "No change set could be sampled this round either. Do not conclude " +
          "that nothing changed — check the code the task describes to see " +
          "whether your earlier findings still stand."

  private def pathList(paths: List[String]): String =
    paths.map("- " + _).mkString("\n")

  /** The diff as a fenced block, or a note when nothing could be sampled. An
    * empty sample means the loop couldn't describe the change, not that none
    * was made (ADR 0011), so the note has to say so.
    */
  private def diffBlock(diff: String): String =
    if LastSent.nothingToShow(diff) then
      "(no change set could be sampled — do not conclude that nothing " +
        "changed; inspect the code the task describes)"
    else s"```diff\n$diff\n```"

/** What the reviewer was last sent about the change set: the sample it compares
  * against, and how much of it reached the conversation — an empty sample
  * reaches it only as the placeholder note.
  */
private[review] enum LastSent(val diff: String):
  case Inline(d: String) extends LastSent(d)
  case SectionsOnly(d: String) extends LastSent(d)
  case PathsOnly(d: String) extends LastSent(d)
  case NoteOnly(d: String) extends LastSent(d)

private[review] object LastSent:
  /** Whether a sample renders as the placeholder note instead of a diff. Shared
    * so the prompt and the recorded [[LastSent]] can't disagree.
    */
  def nothingToShow(sample: String): Boolean = sample.trim.isEmpty

  /** Records a sample sent inline — an empty one reaches the reviewer as the
    * placeholder note, not as a diff.
    */
  def inlined(sample: String): LastSent =
    if nothingToShow(sample) then NoteOnly(sample) else Inline(sample)

/** What a resumed reviewer is told about the change set this round.
  *
  * A resumed reviewer already holds every change set it has been sent. Sending
  * it the same one again, under text saying it was freshly re-sampled, would
  * claim the fixer's edits are inside a diff that predates them, and the
  * reviewer would re-report findings that were already fixed. A
  * [[ReviewDiff.Pinned]] diff produces exactly that repeat.
  */
private[review] enum ReReviewChanges:
  /** Re-sampled, and different from what this reviewer last saw. */
  case Updated(diff: String)

  /** Changed, but past [[ReReviewChanges.InlineThreshold]], so the change set
    * is not sent whole.
    *
    * `changedDiff` is the diff sections of `changed`, bounded to that same
    * threshold, so a resumed conversation doesn't accumulate a copy of the
    * change set per round. `None` when the delta named no path to cut sections
    * for: nothing can be sent, so `changed` is the whole file list and the
    * reviewer reads the files itself.
    *
    * `changed` is the paths whose per-file diff differs from the sample this
    * reviewer last received — under a whole-run diff the delta since its last
    * round is typically one fix, not the run's whole file list. `unchanged` is
    * the rest of the change set; empty when the previous sample gave nothing to
    * compare against, in which case `changed` is every path.
    */
  case TooLarge(
      changedDiff: Option[String],
      changed: List[String],
      unchanged: List[String]
  )

  /** Byte-identical to what this reviewer already holds, so nothing is sent.
    * Carries how that change set reached the conversation: after a [[TooLarge]]
    * round only the changed files' sections — or, where none could be cut, only
    * the paths — are there to point back at, and after an empty sample only the
    * placeholder note, which is no change set at all, so the reviewer must not
    * be told it holds one.
    */
  case AlreadySeen(last: LastSent)

private[review] object ReReviewChanges:
  /** Max diff length (chars) a re-review prompt inlines. Past it the change set
    * is not sent whole: the reviewer gets the sections of the files that
    * changed since its last round, bounded to this same budget, so a resumed
    * conversation doesn't accumulate a copy of the change set per round. Bigger
    * than [[Lint.InlineLintThreshold]] because the diff is the reviewer's
    * primary evidence, not tool output.
    */
  private[review] val InlineThreshold: Int = 16 * 1024

  /** Classify this round's sample against what the reviewer last received. The
    * [[DiffSample]] carries the paths alongside the diff they describe, so
    * [[TooLarge]] can never name a different change set than the one it stands
    * in for.
    *
    * Equality is tested before size, so a [[ReviewDiff.Pinned]] diff never
    * reaches [[TooLarge]]: pinned samples are byte-identical every round, so a
    * resume always classifies [[AlreadySeen]].
    */
  def of(previous: LastSent, current: DiffSample): ReReviewChanges =
    if current.diff == previous.diff then AlreadySeen(previous)
    else if current.diff.length > InlineThreshold then
      val unchanged = unchangedSince(previous.diff, current)
      val changed = current.paths.filterNot(unchanged.toSet)
      // A delta naming no path cannot point the reviewer anywhere (the samples
      // differ outside any parseable section), so fall back to the full list —
      // with no sections to send, since there is nothing to cut them from.
      if changed.isEmpty then TooLarge(None, current.paths, Nil)
      else
        TooLarge(
          Some(
            BoundedDiff.sectionsPayload(current.diff, changed, InlineThreshold)
          ),
          changed,
          unchanged
        )
    else Updated(current.diff)

  /** The paths in `current` whose per-file diff section is byte-identical in
    * `previousDiff` — what [[TooLarge]] may tell a resumed reviewer it need not
    * re-read. A path without a parseable section in both samples (binary
    * change, rename, a cut diff's trailer) is never called unchanged: telling a
    * reviewer to re-read a file it has seen is the safe direction, the reverse
    * is not.
    */
  private def unchangedSince(
      previousDiff: String,
      current: DiffSample
  ): List[String] =
    val prev = BoundedDiff.sectionsByPath(previousDiff)
    val cur = BoundedDiff.sectionsByPath(current.diff)
    current.paths.filter(p =>
      (cur.get(p), prev.get(p)) match
        case (Some(c), Some(pr)) => c == pr
        case _                   => false
    )
