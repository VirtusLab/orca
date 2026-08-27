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

  /** `reported` with findings that point at the same [[Location]] folded into
    * one entry naming every reviewer that raised it. Order follows `reported`,
    * and each surviving entry keeps the first reporter's key, title and
    * suggestion, so what the fixer is asked to echo is what the reader already
    * saw on screen under that reviewer's name.
    *
    * Two findings merge only when both carry a location and it is equal — same
    * file, same line. A finding without a location never merges: with nothing
    * to compare but wording, folding two of them risks dropping a genuine
    * second finding from the record, which costs more than the duplicate saves.
    *
    * The survivor keeps ONE description. Appending each reviewer's own would
    * re-inflate the prompt the merge exists to shrink.
    */
  def merge(reported: List[ReportedIssue]): List[KeyedIssue] =
    // Keyed by the survivor's own key — unique within a round — so a finding
    // absent from this map is one an earlier entry absorbed.
    val coReporters: Map[String, List[String]] =
      reported
        .filter(_.keyed.issue.location.isDefined)
        .groupBy(_.keyed.issue.location)
        .values
        .map(group => group.head.keyed.key -> group.tail.map(_.reviewer))
        .toMap
    reported.flatMap: r =>
      if r.keyed.issue.location.isEmpty then Some(r.keyed)
      else coReporters.get(r.keyed.key).map(withCoReporters(r.keyed, _))

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
