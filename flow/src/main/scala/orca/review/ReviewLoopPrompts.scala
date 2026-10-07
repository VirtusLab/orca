package orca.review

import orca.gitref.CommitHash
import orca.plan.Task
import orca.review.diff.{DiffCoverage, LastSent, ReReviewChanges}
import orca.util.PromptResource

/** Default prompt fragments for the helpers in this package. Each `val` is the
  * replaceable instruction block the helper sends as part of its LLM call; a
  * reply format the helper parses is appended to it regardless. Override via
  * the helper's `instructions` parameter, wrapping a default to extend it:
  *
  * {{{
  * reviewAndFixLoop(
  *   coderSession = coderSession,
  *   reviewers = allReviewers(claude),
  *   task = task,
  *   fixInstructions = ReviewLoopPrompts.Fix +
  *     "\n\nIf you delete a test, mention it in the declined reason."
  * )
  * }}}
  *
  * Source text lives in `src/main/resources/orca/review/prompts/`.
  */
object ReviewLoopPrompts:

  /** Used by [[reviewAndFixLoop]]'s fix step: when to fix a finding and when to
    * decline it.
    */
  val Fix: String =
    PromptResource.load("/orca/review/prompts/fix.md")

  /** Used by [[ReviewerSelector.agentDriven]] to decide which reviewers to run
    * for a given task.
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
    * templates at init; [[openFindingsBlock]] back-references the copy those
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
    * `coverage` says what the diff covers, and names the commit `diff` was
    * sampled against when there is one. The base is sent alongside the diff,
    * never instead of it: it only lets a reviewer read the repo at that commit,
    * and a reviewer with no way to do so is unaffected.
    *
    * `open` matters for a reviewer first activated after round one — see
    * [[reviewAndFixLoop]].
    */
  private[review] def initialReview(
      task: Task,
      userRequest: String,
      diff: String,
      coverage: DiffCoverage,
      open: List[OpenFinding]
  ): String =
    PromptResource.render(
      InitialReviewTemplate,
      "taskTitle" -> task.title.value,
      "taskContext" -> taskContext(task, userRequest),
      "diffIntro" -> diffIntro(coverage),
      "diffBlock" -> diffBlock(diff),
      "baseNote" -> baseNote(coverage.base),
      "openFindings" -> openFindingsBlock(open)
    )

  /** The sentence introducing the initial diff: what the change set covers. A
    * pinned diff says nothing about how far back it reaches.
    */
  private def diffIntro(coverage: DiffCoverage): String =
    coverage match
      case DiffCoverage.Stage(_) =>
        "Diff (everything this task has changed since its stage began, " +
          s"committed or not). $NotGitDiffHead:"
      case DiffCoverage.Since(start) =>
        s"Diff (everything changed since commit ${start.short}, reaching back " +
          s"past the current stage, committed or not). $NotGitDiffHead:"
      case DiffCoverage.Pinned => "Diff (the change set under review):"

  /** Why a reviewer must not fetch the diff itself: work committed during the
    * stage is not in it.
    */
  private val NotGitDiffHead: String =
    "Do not use `git diff HEAD` instead — it does not show work that has " +
      "been committed"

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
  private def baseNote(base: Option[CommitHash]): String =
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

  /** Continuation prompt for a reviewer's session on rounds after the first.
    * The session already holds the reviewer's earlier findings and every change
    * set it has been sent, so `changes` carries only what is new to it —
    * including the base commit, which the initial prompt named and this one
    * therefore doesn't repeat.
    *
    * `open` is every finding still open, each with the reason recorded for it —
    * see [[reviewAndFixLoop]].
    */
  private[review] def reReview(
      changes: ReReviewChanges,
      open: List[OpenFinding]
  ): String =
    PromptResource.render(
      ReReviewTemplate,
      "changes" -> changesBlock(changes),
      "openFindings" -> openFindingsBlock(open)
    )

  /** The findings still open as a paragraph after the change set, carrying its
    * own leading blank line for the same reason as [[baseNote]].
    *
    * Worded as a record of what happened rather than a verdict on the finding.
    * A reviewer told "this was settled" would stop checking, which is the
    * failure this block exists to avoid — the point is to save a round on
    * findings that were already answered, not to withdraw them.
    *
    * Each entry leads with its [[FindingId]], the only place ids are shown: a
    * reviewer names it in `reopens` so a re-report is matched to the entry
    * however it is worded.
    */
  private def openFindingsBlock(open: List[OpenFinding]): String =
    if open.isEmpty then ""
    else
      "\n\nThese findings were reported earlier and are still open. This is " +
        s"the reason recorded for each:\n\n${openFindingLines(open)}" +
        "\n\nIf you report one of them again, however you word it, set " +
        "`reopens` to its id (the text in its brackets)." +
        "\n\nThat is a record of what happened, not a ruling. If you still " +
        "think a finding is real, report it again and say why the reason is " +
        "wrong. \"The plan chose this\" is not on its own a sufficient " +
        "answer for a finding in the always-report categories below — " +
        "re-report such a finding."

  /** One bullet per entry: id, title, where it points, reason — each on one
    * line.
    */
  private def openFindingLines(open: List[OpenFinding]): String =
    open
      .map: f =>
        val where = f.location.fold("")(l => s" (at ${l.text})")
        s"- [${f.id.value}] ${f.titleLine}$where: ${f.reasonLine}"
      .mkString("\n")

  private def changesBlock(changes: ReReviewChanges): String =
    changes match
      case ReReviewChanges.Updated(diff) =>
        "Diff (the change set under review, re-sampled from the same baseline " +
          "as your initial diff, so it includes the fixer's edits whether or " +
          s"not they were committed). $NotGitDiffHead:\n\n${diffBlock(diff)}"
      case ReReviewChanges.Paths(paths) =>
        "The change set under review is too large to include here. These " +
          "files have changed since the baseline of your initial diff — read " +
          s"them directly. $NotGitDiffHead:\n\n" +
          ReReviewChanges.pathsListing(paths)
      case ReReviewChanges.Sections(sections, _, Nil) =>
        "The change set under review is too large to include whole. Below is " +
          "as much of it as fits; any file it does not show is named after " +
          s"it. $NotGitDiffHead:\n\n${diffBlock(sections)}"
      case ReReviewChanges.Sections(sections, _, unchanged) =>
        "The change set under review is too large to include whole. Below is " +
          "the part of it that changed since your previous round; any file " +
          s"that part does not show is named after it. $NotGitDiffHead:\n\n" +
          s"${diffBlock(sections)}\n\nThe rest of the change " +
          "set is unchanged since your previous round — you need not re-read " +
          s"it:\n\n${ReReviewChanges.unchangedListing(unchanged)}"
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

  /** The diff as a fenced block, or a note when nothing could be sampled. An
    * empty sample means the loop couldn't describe the change, not that none
    * was made (ADR 0011), so the note has to say so.
    */
  private def diffBlock(diff: String): String =
    if LastSent.nothingToShow(diff) then
      "(no change set could be sampled — do not conclude that nothing " +
        "changed; inspect the code the task describes)"
    else s"```diff\n$diff\n```"
