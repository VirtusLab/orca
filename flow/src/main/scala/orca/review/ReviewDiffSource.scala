package orca.review

import orca.BoundedDiff
import orca.tools.GitTool

/** A change set as the loop hands it out: the diff text a reviewer is sent, and
  * the paths describing the same change set. Sampled together, so a consumer
  * can never pair one round's diff with another's file list.
  */
private[review] case class DiffSample(diff: String, paths: List[String])

/** Where `reviewAndFixLoop` gets the change set under review. */
enum ReviewDiff:
  /** Everything the enclosing stage has produced since it began (ADR 0018 §2.1)
    * — tracked changes plus newly-created files, `.orca/` bookkeeping excluded
    * — re-sampled at the start of every round and sent to every reviewer that
    * runs, so each round's reviewers see the fixer's edits whether or not it
    * committed them. A sample past [[orca.BoundedDiff.ReviewThreshold]] is cut
    * down to the files that fit plus a list of the ones that didn't, so an
    * outsized change set still produces a prompt that can be sent.
    */
  case SampleFromStage

  /** A caller-pinned diff, sent as given. It changes three things beyond the
    * diff text: reviewers are not told a base commit (the pinned change set
    * need not be the stage's, so naming the stage base would send them to the
    * wrong history); the changed-file list the reviewer picker sees is scraped
    * from the diff text, which can't name a binary change or a 100%-similarity
    * rename; and every re-review round finds the same text, so a resumed
    * reviewer is told there is no new change set even after the fixer edits.
    */
  case Pinned(diff: String)

/** How the loop reads a [[ReviewDiff]] each round. The cases differ in more
  * than the diff text — how reviewers are told to read it, what they may be
  * told about its base, and what the reviewer picker sees as changed files — so
  * each owns all four answers rather than every consumer re-deciding from the
  * enum.
  */
private[review] sealed trait ReviewDiffSource:
  /** This round's change set. Re-sampled each round, so every reviewer that
    * round sees the fixer's later edits.
    */
  def sample(): DiffSample

  /** The commit reviewers are told the diff was sampled against, so a
    * shell-capable reviewer can read past it.
    */
  def base: Option[String]

  /** The sentence introducing the diff in the initial-review prompt. It states
    * what the change set covers, so it can only be claimed by a source that
    * knows.
    */
  def diffIntro: String

  /** The changed files [[ReviewerSelector]] is handed at loop start. */
  def selectorFiles: List[String]

private[review] object ReviewDiffSource:
  /** Everything the enclosing stage has produced since `base` (ADR 0018 §2.1),
    * bounded to [[BoundedDiff.ReviewThreshold]].
    */
  case class Sampled(git: GitTool, base: Option[String])
      extends ReviewDiffSource:
    def sample(): DiffSample =
      val changes = git.reviewChanges(base)
      DiffSample(
        BoundedDiff.reviewPayload(changes.diff, changes.files),
        changes.files.map(_.path)
      )

    def selectorFiles: List[String] = git.changedFiles(base)

    def diffIntro: String =
      "Diff (everything this task has changed since its stage began, " +
        "committed or not). Do not use `git diff HEAD` instead — it does not " +
        "show work that has been committed:"

  /** Reads [[ReviewDiff.Pinned]]: the caller has already decided what a
    * reviewer should see, so the text is sent as given and only that text can
    * name its files.
    */
  case class Pinned(diff: String) extends ReviewDiffSource:
    // The diff is constant, so its file list is scraped once rather than per
    // round.
    private val pinnedSample: DiffSample =
      DiffSample(diff, extractChangedFiles(diff))

    def sample(): DiffSample = pinnedSample
    def base: Option[String] = None
    def selectorFiles: List[String] = pinnedSample.paths

    // Says nothing about how far back the change set reaches: a pinned diff
    // need not be stage-base-to-working-tree.
    def diffIntro: String = "Diff (the change set under review):"

  /** Parse a unified diff and return the changed file paths (the `b/` side of
    * each `+++ b/<path>` header). Filters out `/dev/null` so deletions don't
    * pollute the list. Order matches first appearance in the diff.
    *
    * Only for [[Pinned]], where the diff text is all there is. Diff text can't
    * name every changed file: a binary change and a 100%-similarity rename
    * carry no `+++` header, and for a path with a space the capture includes
    * git's disambiguating trailing tab. [[Sampled]] asks git instead.
    */
  private[review] def extractChangedFiles(diff: String): List[String] =
    "(?m)^\\+\\+\\+ b/(.+)$".r
      .findAllMatchIn(diff)
      .map(_.group(1))
      .filterNot(_ == "/dev/null")
      .toList
      .distinct
