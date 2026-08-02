package orca

import orca.tools.PendingChanges

/** The bounded description of the changes a stage is about to commit, for the
  * default commit-message prompt (see `orca.defaultCommitMessage`). A commit
  * subject needs the file list and the shape of the first hunks, so the whole
  * description is capped at [[InlineThreshold]] rather than sent in full.
  */
private[orca] object CommitDiff:

  /** Max chars [[payload]] ever returns. Text past the budget is dropped rather
    * than spilled to a file for the model to read (the route `orca.review.Lint`
    * takes for lint output): a one-line commit subject doesn't warrant a second
    * read.
    */
  val InlineThreshold: Int = 8 * 1024

  /** Share of [[InlineThreshold]] the summary sections (stat + new-file list)
    * may take between them, leaving the rest for the diff.
    */
  private val SummaryBudget: Int = InlineThreshold / 2

  /** Share of [[SummaryBudget]] the new-file list may take, leaving the rest
    * for the stat — a stage that adds a hundred files still shows what it
    * edited.
    */
  private val NewFilesBudget: Int = SummaryBudget / 2

  private val TruncationMarker: String = "\n…(truncated)"

  /** What the stage is about to commit, in up to three sections: the `git diff
    * --stat` summary of tracked changes, the paths of files new to the repo
    * (which no diff of tracked history reports), and as much of the diff as the
    * rest of the budget allows. `""` when there is nothing to describe, which
    * is the caller's cue to skip the model entirely.
    *
    * The summaries go first because they name every changed file, which a
    * truncated diff head does not. Section headers count against the budget, so
    * the result is never longer than [[InlineThreshold]].
    *
    * Assembled by plain interpolation, never a `stripMargin` block:
    * `stripMargin` runs over the interpolated result, so it would eat the
    * leading `|` of every diff line carrying one — and a context line is `" " +
    * source`, which every `stripMargin` block and markdown table in a repo
    * produces.
    */
  def payload(changes: PendingChanges): String =
    if changes.diff.isBlank && changes.newFiles.isEmpty then ""
    else
      val added = newFilesSection(changes.newFiles)
      val files = statSection(changes.stat, SummaryBudget - added.length)
      val head =
        (List(files, added).filter(_.nonEmpty) :+ "Diff:\n").mkString("\n\n")
      head + bounded(changes.diff, InlineThreshold - head.length)

  private def statSection(stat: String, maxChars: Int): String =
    if stat.isBlank then ""
    else s"Files changed:\n${boundedStat(stat, maxChars)}"

  private def newFilesSection(newFiles: List[String]): String =
    if newFiles.isEmpty then ""
    else s"New files:\n${boundedPaths(newFiles, NewFilesBudget)}"

  /** The stat bounded to `maxChars`, always keeping its last line: git prints
    * the ` N files changed, …` summary there, so a plain head cut would drop
    * the one line stating the change's scope.
    */
  private def boundedStat(stat: String, maxChars: Int): String =
    if stat.length <= maxChars then stat
    else
      val summary = bounded(lastLine(stat), maxChars - 1)
      s"${bounded(stat, maxChars - summary.length - 1)}\n$summary"

  private def lastLine(text: String): String =
    text.linesIterator.toList.lastOption.getOrElse("")

  /** The paths bounded to `maxChars`, cut only between entries: half a path
    * names a file that doesn't exist.
    */
  private def boundedPaths(paths: List[String], maxChars: Int): String =
    val whole = paths.mkString("\n")
    if whole.length <= maxChars then whole
    else
      val room = maxChars - TruncationMarker.length
      // +1 per entry for the separator it will be joined with.
      val kept = paths
        .scanLeft(0)((used, path) => used + path.length + 1)
        .drop(1)
        .zip(paths)
        .takeWhile(_._1 <= room)
        .map(_._2)
      if kept.isEmpty then "" else kept.mkString("\n") + TruncationMarker

  /** `text` cut to at most `maxChars`, marked when anything was dropped so the
    * model reads a cut-off hunk as partial rather than as the whole change. The
    * marker is counted against the budget; when not even it fits, nothing does.
    * The cut backs off a dangling high surrogate — half a pair is not valid
    * UTF-16 and no longer encodes as the character it came from.
    */
  private def bounded(text: String, maxChars: Int): String =
    if text.length <= maxChars then text
    else if maxChars < TruncationMarker.length then ""
    else
      val head = text.take(maxChars - TruncationMarker.length)
      val whole =
        if head.nonEmpty && Character.isHighSurrogate(head.last) then
          head.dropRight(1)
        else head
      whole + TruncationMarker
