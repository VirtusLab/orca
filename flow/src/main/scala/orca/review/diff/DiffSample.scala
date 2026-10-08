package orca.review.diff

/** A change set as the loop hands it out: the diff a reviewer is sent, the
  * paths describing the same change set, and each path's own diff section where
  * the sample has one (see `orca.tools.ReviewSample`). Sampled together, so a
  * consumer can never pair one round's diff with another's file list.
  *
  * A sampled diff is already bounded to `orca.BoundedDiff.ReviewThreshold`.
  *
  * `sections` is not bounded like the diff: it also holds files the diff leaves
  * out.
  */
private[review] case class DiffSample(
    text: DiffText,
    paths: List[String],
    sections: Map[String, String]
)

private[review] object DiffSample:
  val empty: DiffSample = DiffSample(DiffText.Inline(""), Nil, Map.empty)

/** How a diff reaches a reviewer. */
private[review] enum DiffText:
  /** Sent in the prompt. */
  case Inline(diff: String)

  /** Left in a repo-relative file the reviewer reads. */
  case InFile(path: String)

  /** Characters this diff adds to a prompt. */
  def promptLength: Int = this match
    case Inline(diff) => diff.length
    case InFile(_)    => 0
