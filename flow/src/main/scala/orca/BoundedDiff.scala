package orca

import orca.tools.{ChangedFile, FileChange, PendingChanges}

/** Renders a change set into a prompt under a size budget, for the two callers
  * that inline one: the default commit-message prompt ([[commitPayload]], see
  * `orca.defaultCommitMessage`) and the reviewer's initial prompt
  * ([[reviewPayload]], see `orca.review.reviewAndFixLoop`).
  *
  * Both keep the same rule: what is left out is still named. A commit subject
  * gets the `--stat` summary ahead of the diff; a reviewer gets a trailer
  * listing the files the diff it was sent does not show. Neither ever returns
  * more than its threshold, so a change set of any size produces a prompt that
  * can be sent.
  *
  * Text is assembled by plain interpolation, never a `stripMargin` block:
  * `stripMargin` runs over the interpolated result, so it would eat the leading
  * `|` of every diff line carrying one — and a context line is `" " + source`,
  * which every `stripMargin` block and markdown table in a repo produces.
  */
private[orca] object BoundedDiff:

  /** Max chars [[commitPayload]] ever returns. Text past the budget is dropped
    * rather than spilled to a file for the model to read (the route
    * `orca.review.Lint` takes for lint output): a one-line commit subject
    * doesn't warrant a second read.
    */
  val CommitThreshold: Int = 8 * 1024

  /** Max chars [[reviewPayload]] ever returns.
    *
    * Sized as a safety valve against a request that cannot be sent, not as a
    * saving: at ~4 chars per token a 128 KiB diff is ~32k tokens, which on top
    * of a reviewer's ~33k-token preamble makes a ~65k first prompt — well
    * inside a 200k context window. Measured over this repo's last 200 commits,
    * a cap here leaves ~96% of change sets inlined whole (the median is 6 KB),
    * while the largest, at 2.1 MB, fits no context window at all.
    *
    * Much larger than `orca.review.ReReviewChanges.InlineThreshold`, which
    * bounds a different thing: that one keeps a resumed conversation from
    * accumulating a copy of the diff per round, which is a cost question, and
    * cost bites far below the size at which a request stops being sendable.
    */
  val ReviewThreshold: Int = 128 * 1024

  /** Share of [[CommitThreshold]] the summary sections (stat + new-file list)
    * may take between them, leaving the rest for the diff.
    */
  private val SummaryBudget: Int = CommitThreshold / 2

  /** Share of [[SummaryBudget]] the new-file list may take, leaving the rest
    * for the stat — a stage that adds a hundred files still shows what it
    * edited.
    */
  private val NewFilesBudget: Int = SummaryBudget / 2

  /** Share of [[ReviewThreshold]] the omitted-file trailer may take, leaving
    * the rest for the diff itself.
    */
  private val TrailerBudget: Int = ReviewThreshold / 2

  private val TruncationMarker: String = "\n…(truncated)"

  /** The line git starts each file's section with, at column 0. No content line
    * can imitate it: every line inside a hunk carries a ` `/`+`/`-` prefix.
    */
  private val FileHeader: String = "diff --git "

  /** What the stage is about to commit, in up to three sections: the `git diff
    * --stat` summary of tracked changes, the paths of files new to the repo
    * (which no diff of tracked history reports), and as much of the diff as the
    * rest of the budget allows. `""` when there is nothing to describe, which
    * is the caller's cue to skip the model entirely.
    *
    * The summaries go first because they name every changed file, which a
    * truncated diff head does not. Section headers count against the budget, so
    * the result is never longer than [[CommitThreshold]].
    */
  def commitPayload(changes: PendingChanges): String =
    if changes.diff.isBlank && changes.newFiles.isEmpty then ""
    else
      val added = newFilesSection(changes.newFiles)
      val files = statSection(changes.stat, SummaryBudget - added.length)
      val head =
        (List(files, added).filter(_.nonEmpty) :+ "Diff:\n").mkString("\n\n")
      head + bounded(changes.diff, CommitThreshold - head.length)

  /** The change set a reviewer is sent, bounded to [[ReviewThreshold]].
    *
    * Under the threshold the diff is returned untouched. Over it the result is
    * as many whole file sections as fit, then a trailer naming every file whose
    * section was left out, with its line counts. Whole sections: cutting
    * mid-file hands a reviewer part of a change, and a reviewer that judges a
    * fragment as if it were the whole reports findings the rest of the file
    * answers.
    *
    * `changed` is the change set's file list as git reports it (see
    * `GitTool.changedFileStats`), not one scraped from the diff body, which
    * shows neither a binary change nor a 100%-similarity rename. Together with
    * the sections rendered it covers the whole change set: nothing is left out
    * without being named.
    */
  def reviewPayload(diff: String, changed: List[ChangedFile]): String =
    if diff.length <= ReviewThreshold then diff
    else
      val head = wholeFilesWithin(diff, ReviewThreshold - trailerMax(changed))
      val shown = head.linesIterator.filter(_.startsWith(FileHeader)).toList
      head + trailer(changed.filterNot(f => isShown(shown, f.path)))

  /** The longest prefix of `diff` within `maxChars` that ends where a file's
    * section ends, or `""` when not even the first section fits — the honest
    * answer for a single file too large to send.
    */
  private def wholeFilesWithin(diff: String, maxChars: Int): String =
    fileStarts(diff).filter(_ <= maxChars).lastOption.fold("")(diff.take)

  /** The offset at which each file's section starts. */
  private def fileStarts(diff: String): List[Int] =
    diff.linesWithSeparators
      .scanLeft((0, "")): (soFar, line) =>
        (soFar._1 + soFar._2.length, line)
      .drop(1)
      .collect:
        case (offset, line) if line.startsWith(FileHeader) => offset
      .toList

  /** Is this file's own section among the ones rendered, `headers` being their
    * header lines?
    *
    * Matched on the header line, rather than on a path parsed out of it. A
    * header git had to quote (a `"` or a non-ASCII byte in the name) doesn't
    * match, so its file is reported as not shown although it was — the safe
    * direction of the two: the reader is told to open a file it has already
    * seen, never left unaware of one it hasn't. Same for a path `reviewDiff`
    * could only announce (`# skipped …`) rather than render, and for a workDir
    * below the repository root, where the header names the path from the root
    * and the file list names it from `workDir`.
    */
  private def isShown(headers: List[String], path: String): Boolean =
    headers.exists(_.endsWith(s" b/$path"))

  private val TrailerHead: String =
    s"\n# The diff above was cut short at $ReviewThreshold characters. It " +
      "does not show\n# the changes to the files below — read those files " +
      "directly.\n"

  /** The note closing a cut-short diff whose file list names nothing to add.
    * Reachable because the diff and the file list are two separate git reads
    * (see `orca.review.ReviewFixLoop`), so an edit landing between them can
    * leave every cut file unnamed. The cut is stated anyway: a reviewer that
    * isn't told the diff is partial judges a fragment as if it were the whole.
    */
  private val UnnamedTrailer: String =
    s"\n# The diff above was cut short at $ReviewThreshold characters. Part of " +
      "the change\n# is not shown, and the file list needed to name it was not " +
      "available.\n"

  /** The note closing a cut-short review diff: that it was cut, and every file
    * the rendered part doesn't show. Every line is a `#` comment so none of it
    * can read as part of a hunk — `- path` would look like a deleted line of
    * source — and the entries are indented under the sentence introducing them.
    */
  private def trailer(omitted: List[ChangedFile]): String =
    if omitted.isEmpty then UnnamedTrailer
    else TrailerHead + boundedEntries(omitted.map(entryLine), TrailerBudget)

  /** The longest [[trailer]] any subset of `all` can produce, which is what the
    * diff has to be sized against — the omitted set isn't known until the diff
    * has been cut. Rendering a subset is not always shorter than rendering the
    * whole list: once the entries are past [[TrailerBudget]] both fill it, and
    * dropping short entries from the front can let longer ones in. So the bound
    * is the unbounded entry length, capped at the budget — not what rendering
    * `all` happens to produce, which [[boundedEntries]] can cut short at the
    * first entry too long to fit while a subset of it renders longer.
    */
  private def trailerMax(all: List[ChangedFile]): Int =
    // +1 per entry for the separator `boundedEntries` joins them with.
    val entries = all.map(entryLine(_).length + 1).sum
    math.max(
      UnnamedTrailer.length,
      TrailerHead.length + math.min(entries, TrailerBudget)
    )

  private def entryLine(file: ChangedFile): String =
    val size = file.change match
      case FileChange.Lines(added, deleted) => s"+$added -$deleted"
      case FileChange.Binary                => "binary"
      case FileChange.New                   => "new file"
    s"#   ${file.path} ($size)"

  private def statSection(stat: String, maxChars: Int): String =
    if stat.isBlank then ""
    else s"Files changed:\n${boundedStat(stat, maxChars)}"

  private def newFilesSection(newFiles: List[String]): String =
    if newFiles.isEmpty then ""
    else s"New files:\n${boundedEntries(newFiles, NewFilesBudget)}"

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

  /** The entries bounded to `maxChars`, cut only between them: half a path
    * names a file that doesn't exist.
    */
  private def boundedEntries(entries: List[String], maxChars: Int): String =
    val whole = entries.mkString("\n")
    if whole.length <= maxChars then whole
    else
      val room = maxChars - TruncationMarker.length
      // +1 per entry for the separator it will be joined with.
      val kept = entries
        .scanLeft(0)((used, entry) => used + entry.length + 1)
        .drop(1)
        .zip(entries)
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
