package orca

/** The bounded description of a stage's working-tree changes that the default
  * commit-message prompt carries (see `orca.defaultCommitMessage`). A commit
  * subject needs the file list and the shape of the first hunks; sending a
  * whole stage diff to a model to get one line back is what this bounds.
  *
  * The change set described is the one the stage is about to commit — `git
  * commit`'s own `add -A` — so it spans tracked edits AND files new to the
  * repo, minus orca's `.orca/` bookkeeping.
  */
private[orca] object CommitDiff:

  /** Max chars of change text — stat plus diff — inlined into the
    * commit-message prompt, roughly 2k tokens. Text past the budget is dropped
    * rather than spilled to a file for the model to read (the route
    * `orca.review.Lint` takes for lint output): a one-line commit subject
    * doesn't warrant a second read.
    */
  val InlineThreshold: Int = 8 * 1024

  /** Share of [[InlineThreshold]] the summary sections (stat + new-file list)
    * may take between them. The remainder is reserved for the diff, so a change
    * touching hundreds of files can't starve the hunks down to nothing.
    */
  private val SummaryBudget: Int = InlineThreshold / 2

  /** Share of [[SummaryBudget]] the new-file list may take, leaving the rest
    * for the stat — a stage that adds a hundred files still shows what it
    * edited.
    */
  private val NewFilesBudget: Int = SummaryBudget / 2

  private val TruncationMarker: String = "\n…(truncated)"

  /** What the stage is about to commit, in three sections: the `git diff
    * --stat` summary of tracked changes, the paths of files new to the repo
    * (which no diff of tracked history reports, though the commit includes
    * them), and as much of the diff — `reviewDiff`, so new files' contents are
    * in it too — as the remaining [[InlineThreshold]] budget allows. `""` when
    * there is nothing to describe, which is the caller's cue to skip the model
    * entirely.
    *
    * The summaries go first because they name every changed file, which a
    * truncated diff head does not.
    *
    * Assembled by plain interpolation, never a `stripMargin` block:
    * `stripMargin` runs over the interpolated result, so it would eat the
    * leading `|` of every diff line carrying one — and a context line is `" " +
    * source`, which every `stripMargin` block and markdown table in a repo
    * produces.
    */
  def payload(stat: String, newFiles: List[String], diff: String): String =
    if diff.isBlank && newFiles.isEmpty then ""
    else
      val added = newFilesSection(newFiles)
      val files = boundedStat(stat, SummaryBudget - added.length)
      val hunks = bounded(diff, InlineThreshold - files.length - added.length)
      s"Files changed:\n$files\n$added\nDiff:\n$hunks"

  /** The new-file paths as their own section, empty when there are none — a
    * heading promising a list and then showing none reads as information.
    */
  private def newFilesSection(newFiles: List[String]): String =
    if newFiles.isEmpty then ""
    else s"\nNew files:\n${bounded(newFiles.mkString("\n"), NewFilesBudget)}\n"

  /** The stat bounded to `maxChars`, always keeping its last line: git prints
    * the ` N files changed, …` summary there, so a plain head cut would drop
    * the one line stating the change's scope.
    */
  private def boundedStat(stat: String, maxChars: Int): String =
    if stat.length <= maxChars then stat
    else
      val summary = stat.linesIterator.toList.lastOption.getOrElse("")
      val perFile = bounded(stat, (maxChars - summary.length - 1).max(0))
      s"$perFile\n$summary"

  /** `text` cut to at most `maxChars`, marked when anything was dropped so the
    * model reads a cut-off hunk as partial rather than as the whole change. The
    * marker is counted against the budget, and the cut backs off a dangling
    * high surrogate — half a pair is not valid UTF-16, and the JSON writer that
    * puts the prompt on the wire rejects it.
    */
  private def bounded(text: String, maxChars: Int): String =
    if text.length <= maxChars then text
    else
      val head = text.take((maxChars - TruncationMarker.length).max(0))
      val whole =
        if head.nonEmpty && Character.isHighSurrogate(head.last) then
          head.dropRight(1)
        else head
      whole + TruncationMarker
