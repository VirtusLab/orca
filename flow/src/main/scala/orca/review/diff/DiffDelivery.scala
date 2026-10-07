package orca.review.diff

import orca.BoundedDiff

/** What one reviewer is sent about the change set in a round. */
private[review] enum DiffMessage:
  /** The reviewer's first round: the sample's diff, whole. */
  case Initial(sample: DiffSample)

  /** A later round, in a conversation that already holds earlier change sets:
    * only what is new to it.
    */
  case ReReview(changes: ReReviewChanges)

/** One reviewer's round: the message to send it, and what it was sent, which
  * its next round compares against.
  */
private[review] case class DiffDelivery(
    message: DiffMessage,
    lastSent: LastSent
)

private[review] object DiffDelivery:
  /** A reviewer's first round. */
  def first(current: DiffSample): DiffDelivery =
    DiffDelivery(DiffMessage.Initial(current), LastSent.inlined(current))

  /** A later round, given what the reviewer was sent in its previous one.
    *
    * Equality is tested before size, so a [[ReviewDiffSource.Pinned]] diff
    * never reaches a cut: pinned samples are byte-identical every round. The
    * whole sample is compared, sections included: `diff` may leave out an
    * edited file.
    */
  def next(previous: LastSent, current: DiffSample): DiffDelivery =
    if current == previous.sample then
      // Nothing is sent, so the next round compares against the same sample.
      DiffDelivery(
        DiffMessage.ReReview(ReReviewChanges.AlreadySeen(previous)),
        previous
      )
    else if current.diff.length > ReReviewChanges.InlineThreshold then
      cut(previous, current)
    else
      DiffDelivery(
        DiffMessage.ReReview(ReReviewChanges.Updated(current.diff)),
        LastSent.inlined(current)
      )

  /** A change set too large to re-send whole. `lastSent` still records the
    * whole sample, not the part that was sent: the next round compares against
    * all of it, so a change that rewrites those files without adding or
    * removing any must still register.
    */
  private def cut(previous: LastSent, current: DiffSample): DiffDelivery =
    val unchanged = unchangedSince(previous.sample, current)
    val changed = current.paths.filterNot(unchanged.toSet)
    val pathsOnly =
      DiffDelivery(
        DiffMessage.ReReview(ReReviewChanges.Paths(current.paths)),
        LastSent.PathsOnly(current)
      )
    // A delta naming no path cannot point the reviewer anywhere (the samples
    // differ outside every file's section, e.g. in a `# skipped` line), so fall
    // back to the full list — with no sections to send, since there is nothing
    // to cut them from.
    if changed.isEmpty then pathsOnly
    else
      BoundedDiff.sectionsPayload(
        current.sections,
        changed,
        ReReviewChanges.InlineThreshold - ReReviewChanges.PathListBudget
      ) match
        case BoundedDiff.SectionsCut.Rendered(sections) =>
          DiffDelivery(
            DiffMessage.ReReview(
              ReReviewChanges.Sections(sections, changed, unchanged)
            ),
            LastSent.SectionsOnly(current)
          )
        // One file bigger than the budget leaves room for no section at all,
        // and a payload of nothing but a trailer would claim to carry sections
        // it doesn't have.
        case BoundedDiff.SectionsCut.NothingFits => pathsOnly

  /** The paths in `current` whose diff section is byte-identical in `previous`
    * — what [[ReReviewChanges.Sections]] may tell a resumed reviewer it need
    * not re-read. A path without a section in both samples is never called
    * unchanged: its edits cannot be compared.
    */
  private def unchangedSince(
      previous: DiffSample,
      current: DiffSample
  ): List[String] =
    current.paths.filter(p =>
      (current.sections.get(p), previous.sections.get(p)) match
        case (Some(c), Some(pr)) => c == pr
        case _                   => false
    )

/** What the reviewer was last sent about the change set: the sample it compares
  * against, and how much of it reached the conversation — an empty sample
  * reaches it only as the placeholder note.
  */
private[review] enum LastSent(val sample: DiffSample):
  case Inline(s: DiffSample) extends LastSent(s)
  case SectionsOnly(s: DiffSample) extends LastSent(s)
  case PathsOnly(s: DiffSample) extends LastSent(s)
  case NoteOnly(s: DiffSample) extends LastSent(s)

private[review] object LastSent:
  /** Whether a sample renders as the placeholder note instead of a diff. Shared
    * so the prompt and the recorded [[LastSent]] can't disagree.
    */
  def nothingToShow(diff: String): Boolean = diff.trim.isEmpty

  /** Records a sample sent inline — an empty one reaches the reviewer as the
    * placeholder note, not as a diff.
    */
  def inlined(sample: DiffSample): LastSent =
    if nothingToShow(sample.diff) then NoteOnly(sample) else Inline(sample)

/** What a resumed reviewer is told about the change set this round.
  *
  * A resumed reviewer already holds every change set it has been sent. Sending
  * it the same one again, under text saying it was freshly re-sampled, would
  * claim the fixer's edits are inside a diff that predates them, and the
  * reviewer would re-report findings that were already fixed. A
  * [[ReviewDiffSource.Pinned]] diff produces exactly that repeat.
  */
private[review] enum ReReviewChanges:
  /** Re-sampled, and different from what this reviewer last saw. */
  case Updated(diff: String)

  /** Changed, but past [[ReReviewChanges.InlineThreshold]], so only the diff
    * sections of the files that changed since this reviewer's last round are
    * sent — bounded so a resumed conversation accumulates at most that
    * threshold per round.
    *
    * `changed` is the paths whose per-file diff differs from the sample this
    * reviewer last received — under a whole-run diff the delta since its last
    * round is typically one fix, not the run's whole file list. Nothing but the
    * run's trace reads it, where it indexes which files a round sent.
    *
    * `unchanged` is the rest of the change set; empty when the previous sample
    * gave nothing to compare against, in which case `changed` is every path.
    */
  case Sections(
      diff: String,
      changed: List[String],
      unchanged: List[String]
  )

  /** Changed and past the threshold, with no section to send: either the delta
    * named no path to cut sections for, or not even the first section fits the
    * budget. `paths` is the whole change set, which the reviewer reads itself.
    */
  case Paths(paths: List[String])

  /** Byte-identical to what this reviewer already holds, so nothing is sent.
    * Carries how that change set reached the conversation: after a [[Sections]]
    * round only the changed files' sections, after a [[Paths]] round only the
    * paths, and after an empty sample only the placeholder note, which is no
    * change set at all, so the reviewer must not be told it holds one.
    */
  case AlreadySeen(last: LastSent)

private[review] object ReReviewChanges:
  /** Max diff length (chars) a re-review prompt inlines. Past it the change set
    * is not sent whole: the reviewer gets the sections of the files that
    * changed since its last round, bounded so the whole block stays within this
    * same budget — a resumed conversation accumulates at most that much per
    * round. Bigger than `orca.review.Lint.InlineLintThreshold` because the diff
    * is the reviewer's primary evidence, not tool output.
    */
  val InlineThreshold: Int = 16 * 1024

  /** Share of [[InlineThreshold]] the path list naming the rest of the change
    * set may take, leaving the rest for the sections. Reserved whether or not
    * that list turns out to be empty, so one number bounds the whole block.
    */
  val PathListBudget: Int = InlineThreshold / 4
