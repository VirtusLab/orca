package orca.review

import orca.BoundedDiff
import orca.tools.GitTool

/** A change set as the loop hands it out: the diff text a reviewer is sent, and
  * the paths describing the same change set. Sampled together, so a consumer
  * can never pair one round's diff with another's file list.
  */
private[review] case class DiffSample(diff: String, paths: List[String])

/** Where `reviewAndFixLoop` gets the change set under review. The two cases
  * differ in more than the diff text — what reviewers may be told about its
  * base, and what the reviewer picker sees as changed files — so each owns all
  * three answers rather than every consumer re-deciding from an `Option`.
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

  /** A caller-pinned diff, sent as given — a caller that pins the diff has
    * already decided what a reviewer should see. It may describe a change set
    * that isn't stage-base-to-working-tree, so naming the stage base would send
    * the reviewer to the wrong history, and only the diff text itself can name
    * its files.
    */
  case class Pinned(diff: String) extends ReviewDiffSource:
    // The diff is constant, so its file list is scraped once rather than per
    // round.
    private val pinnedSample: DiffSample =
      DiffSample(diff, extractChangedFiles(diff))

    def sample(): DiffSample = pinnedSample
    def base: Option[String] = None
    def selectorFiles: List[String] = pinnedSample.paths

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
