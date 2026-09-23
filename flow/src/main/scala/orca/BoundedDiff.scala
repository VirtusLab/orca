package orca

import orca.tools.{ChangedFile, FileChange, PendingChanges, ReviewSample}

/** Renders a change set into a prompt under a size budget, for the four callers
  * that inline one: the default commit-message prompt ([[commitPayload]], see
  * `orca.defaultCommitMessage`), the reviewer's initial prompt
  * ([[reviewPayload]], see `orca.review.reviewAndFixLoop`), a resumed
  * reviewer's per-round delta ([[sectionsPayload]], see
  * `orca.review.ReReviewChanges`) and the PR summariser's ([[prPayload]], see
  * `orca.pr.summarisePr`).
  *
  * The first three keep the same rule: what is left out is still named. A
  * commit subject gets the `--stat` summary ahead of the diff; a reviewer gets
  * a trailer listing the files the diff it was sent does not show.
  * [[prPayload]] only marks that a cut happened. None returns more than its
  * budget (plus, for [[prPayload]], its marker), so a change set of any size
  * produces a prompt that can be sent.
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

  /** The size both [[reviewPayload]] and [[prPayload]] bound a diff to:
    * [[reviewPayload]] never returns more, [[prPayload]] cuts its head to it
    * and appends a marker on top.
    *
    * Sized as a safety valve against a request that cannot be sent, not as a
    * saving: at ~4 chars per token a 128 KiB diff is ~32k tokens, which on top
    * of a reviewer's ~33k-token preamble makes a ~65k first prompt — well
    * inside a 200k context window. Measured over this repo's last 200 commits,
    * a cap here leaves ~96% of change sets inlined whole (the median is 6 KB),
    * while the largest, at 2.1 MB, fits no context window at all.
    *
    * Much larger than `orca.review.ReReviewChanges.InlineThreshold`, which
    * bounds a different thing: that one bounds what a single re-review prompt
    * inlines, so a resumed conversation accumulates at most that much per round
    * — a cost question, and cost bites far below the size at which a request
    * stops being sendable.
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
    * as many whole file sections as fit, in the sample's file order, then a
    * trailer naming every other file, with its line counts. Whole sections:
    * cutting mid-file hands a reviewer part of a change, and a reviewer that
    * judges a fragment as if it were the whole reports findings the rest of the
    * file answers. Together the sections and the trailer cover every file in
    * the sample: nothing is left out without being named.
    */
  def reviewPayload(sample: ReviewSample): String =
    if sample.diff.length <= ReviewThreshold then sample.diff
    else
      val withSections =
        sample.files.flatMap(f => sample.sections.get(f.path).map(f -> _))
      val room = ReviewThreshold - trailerMax(sample.files)
      val shown = packed(withSections, room)(_._2.length)
      val head = shown.map(_._2).mkString
      val shownFiles = shown.map(_._1).toSet
      head + trailer(sample.files.filterNot(shownFiles), head.length)

  /** What [[sectionsPayload]] could cut for its caller. */
  private[orca] enum SectionsCut:
    /** At least one whole file section fit, with a trailer naming every
      * requested path the payload does not show.
      */
    case Rendered(payload: String)

    /** Not even the first section fit within the budget — one ordinary large
      * file is enough. There is nothing to send, so the caller has to fall back
      * to naming the paths.
      */
    case NothingFits

  /** The `sections` (by path) covering `paths`, bounded to `maxChars`, with a
    * trailer naming every one of `paths` the result does not show.
    *
    * The paths-only sibling of [[reviewPayload]], for a caller that has a file
    * list rather than [[ChangedFile]]s: same whole-section cut, but with no
    * line counts to report, so a dropped path is named on its own. A path with
    * no entry in `sections` is named in the trailer rather than silently
    * absent; a path repeated in `paths` is rendered once.
    *
    * `maxChars` is the caller's budget, not [[ReviewThreshold]]: this renders
    * into a prompt sent every round, where the bound is what the conversation
    * may accumulate rather than what a request can carry.
    */
  def sectionsPayload(
      sections: Map[String, String],
      paths: List[String],
      maxChars: Int
  ): SectionsCut =
    val wanted = paths.distinct
    val available = wanted.flatMap(p => sections.get(p).map(p -> _))
    val room = maxChars - pathTrailerMax(wanted, maxChars)
    val shown = packed(available, room)(_._2.length)
    if shown.isEmpty then SectionsCut.NothingFits
    else
      SectionsCut.Rendered(
        shown.map(_._2).mkString +
          pathTrailer(wanted.filterNot(shown.map(_._1).toSet), maxChars)
      )

  private val PathTrailerHead: String =
    "\n# The sections above do not show the changes to the files below — " +
      "read those files directly.\n"

  /** The note closing a cut-short section payload. Every line is a `#` comment
    * for the same reason as [[trailer]]'s: `- path` would read as a deleted
    * line of source.
    */
  private def pathTrailer(omitted: List[String], maxChars: Int): String =
    if omitted.isEmpty then ""
    else
      PathTrailerHead +
        boundedEntries(omitted.map("#   " + _), pathTrailerBudget(maxChars))

  /** Share of a sections payload's budget its trailer may take, leaving the
    * rest for diff text. A quarter, where [[TrailerBudget]] takes half of
    * [[ReviewThreshold]]: this budget is an order of magnitude smaller, so half
    * of it would spend most of a round's payload on filenames.
    */
  private def pathTrailerBudget(maxChars: Int): Int = maxChars / 4

  /** The longest [[pathTrailer]] any subset of `all` can produce — what the
    * sections have to be sized against, for the same reason as [[trailerMax]].
    */
  private def pathTrailerMax(all: List[String], maxChars: Int): Int =
    // 4 for the `#   ` prefix, 1 for the separator `boundedEntries` joins with.
    val entries = all.map(_.length + 5).sum
    PathTrailerHead.length + math.min(entries, pathTrailerBudget(maxChars))

  /** `paths` as a `- path` list, bounded to `maxChars` and cut only between
    * entries, marked when anything was dropped. For a prompt block that names
    * files alongside a payload: without this the list is outside every budget,
    * and a change set of a few hundred files makes filenames most of what is
    * sent.
    */
  def pathList(paths: List[String], maxChars: Int): String =
    boundedEntries(paths.map("- " + _), maxChars)

  /** The branch diff a PR summariser is sent: under [[ReviewThreshold]] the
    * diff itself, over it a head cut plus a marker, so at most that threshold
    * plus the marker.
    *
    * A head cut where [[reviewPayload]] cuts on file boundaries and names what
    * it dropped: a title and body describe what the branch does, which the
    * leading files show, and a summary survives a mid-hunk cut where a review
    * does not.
    */
  def prPayload(diff: String): String =
    if diff.length <= ReviewThreshold then diff
    else
      withoutDanglingSurrogate(diff.take(ReviewThreshold)) +
        s"\n\n[diff cut at $ReviewThreshold characters — the summary covers " +
        "the leading files only]"

  /** `shownChars` is the length of the diff as sent, which is under
    * [[ReviewThreshold]] by whatever the trailer takes — up to
    * [[TrailerBudget]] when the file list is long. Reporting the threshold
    * instead would tell the reviewer it got roughly twice the diff it did.
    */
  private def trailerHead(shownChars: Int): String =
    s"\n# The diff above was cut short at $shownChars characters. It " +
      "does not show\n# the changes to the files below — read those files " +
      "directly.\n"

  /** The note closing a cut-short review diff: that it was cut, and every file
    * the rendered part doesn't show. Every line is a `#` comment so none of it
    * can read as part of a hunk — `- path` would look like a deleted line of
    * source — and the entries are indented under the sentence introducing them.
    *
    * `omitted` is never empty when a cut happened: a cut always drops at least
    * one file's section, and the sample names every file.
    */
  private def trailer(omitted: List[ChangedFile], shownChars: Int): String =
    trailerHead(shownChars) +
      boundedEntries(omitted.map(entryLine), TrailerBudget)

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
    // Sized with the threshold in place of the diff length the trailer will
    // report: the latter is always smaller, so never renders more digits.
    trailerHead(ReviewThreshold).length + math.min(entries, TrailerBudget)

  private def entryLine(file: ChangedFile): String =
    val size = file.change match
      // `+0 -0` reads as "nothing changed", which is never why a file is in a
      // change set. Left this vague because the counts are all this side has:
      // see `FileChange.Lines` for the causes they cannot tell apart.
      case FileChange.Lines(0, 0)           => "no lines changed"
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
      // +1 per entry for the separator it will be joined with.
      val kept =
        packed(entries, maxChars - TruncationMarker.length)(_.length + 1)
      if kept.isEmpty then "" else kept.mkString("\n") + TruncationMarker

  /** The longest prefix of `entries` whose sizes sum to at most `room` — the
    * cut every bounded list here makes, taken between entries rather than
    * inside one.
    */
  private def packed[A](entries: List[A], room: Int)(
      size: A => Int
  ): List[A] =
    entries
      .scanLeft(0)((used, entry) => used + size(entry))
      .drop(1)
      .zip(entries)
      .takeWhile(_._1 <= room)
      .map(_._2)

  /** `text` cut to at most `maxChars`, marked when anything was dropped so the
    * model reads a cut-off hunk as partial rather than as the whole change. The
    * marker is counted against the budget; when not even it fits, nothing does.
    */
  private def bounded(text: String, maxChars: Int): String =
    if text.length <= maxChars then text
    else if maxChars < TruncationMarker.length then ""
    else
      withoutDanglingSurrogate(text.take(maxChars - TruncationMarker.length)) +
        TruncationMarker

  /** `text` minus a trailing high surrogate: half a pair is not valid UTF-16
    * and no longer encodes as the character it came from.
    */
  private def withoutDanglingSurrogate(text: String): String =
    if text.nonEmpty && Character.isHighSurrogate(text.last) then
      text.dropRight(1)
    else text
