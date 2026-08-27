package orca.review

// Folding cross-reviewer duplicates into the one list handed to the fixer.
// Kept out of `ReviewLoop.scala` so that file holds only the loop, and out of
// capture checking, which none of this needs (see FixRequest.scala).

/** One reviewer's finding, tagged with who reported it — what [[merge]] needs
  * and [[KeyedIssue]] alone doesn't say.
  */
private[review] case class ReportedIssue(reviewer: String, keyed: KeyedIssue)

/** Cross-reviewer duplicate folding for a round's findings. */
private[review] object DuplicateFindings:

  /** `reported` with findings that point at the same `file:line` folded into
    * one entry naming every reviewer that raised it. Order follows `reported`,
    * and each surviving entry keeps the first reporter's key, title and
    * suggestion, so what the fixer is asked to echo is what the reader already
    * saw on screen under that reviewer's name.
    *
    * Two findings merge only when both name the same file AND the same line,
    * and only across different reviewers. A finding without a line never
    * merges: two file-scope findings in one file are two findings, and with
    * nothing to compare but wording, folding them drops a genuine second
    * finding from the record — which costs more than the duplicate saves. One
    * reviewer's two findings on one line are two findings for the same reason.
    *
    * The survivor keeps ONE description. Appending each reviewer's own would
    * re-inflate the prompt the merge exists to shrink.
    */
  def merge(reported: List[ReportedIssue]): List[KeyedIssue] =
    val groups =
      reported.filter(pinnedToLine).groupBy(_.keyed.issue.location).values
    // Keyed by the survivor's own key — unique within a round.
    val coReporters: Map[String, List[String]] =
      groups.map(g => g.head.keyed.key -> absorbedBy(g).map(_.reviewer)).toMap
    val absorbed: Set[String] =
      groups.flatMap(absorbedBy).map(_.keyed.key).toSet
    reported.collect:
      case r if !absorbed.contains(r.keyed.key) =>
        withCoReporters(r.keyed, coReporters.getOrElse(r.keyed.key, Nil))

  /** Does this finding name a line, and so take part in the folding at all? */
  private def pinnedToLine(r: ReportedIssue): Boolean =
    r.keyed.issue.location.exists(_.line.isDefined)

  /** The entries of a same-location group that fold into its first: the ones
    * another reviewer reported. The head's own reviewer never appears — it
    * would be named as its own co-reporter, and its second finding on that line
    * would be lost.
    */
  private def absorbedBy(group: List[ReportedIssue]): List[ReportedIssue] =
    group.tail.filter(_.reviewer != group.head.reviewer)

  /** `keyed` with the reviewers that also reported it named at the end of its
    * description, where [[FixRequest]] renders it. Not in the title: the title
    * is what [[FixOutcome.reconcile]] resolves the fixer's echo against.
    */
  private def withCoReporters(
      keyed: KeyedIssue,
      others: List[String]
  ): KeyedIssue =
    if others.isEmpty then keyed
    else
      val note = s"Also reported by ${others.distinct.mkString(", ")}."
      val description = keyed.issue.description.trim match
        case ""   => note
        case text => s"$text\n\n$note"
      keyed.copy(issue = keyed.issue.copy(description = description))
