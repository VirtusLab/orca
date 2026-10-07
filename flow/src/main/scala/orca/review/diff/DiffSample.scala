package orca.review.diff

/** A change set as the loop hands it out: the diff text a reviewer is sent, the
  * paths describing the same change set, and each path's own diff section where
  * the sample has one (see `orca.tools.ReviewSample`). Sampled together, so a
  * consumer can never pair one round's diff with another's file list.
  *
  * A sampled `diff` is already bounded to `orca.BoundedDiff.ReviewThreshold`; a
  * pinned one is as the caller gave it.
  *
  * `sections` is not bounded like `diff`: it also holds files `diff` leaves
  * out.
  */
private[review] case class DiffSample(
    diff: String,
    paths: List[String],
    sections: Map[String, String]
)

private[review] object DiffSample:
  val empty: DiffSample = DiffSample("", Nil, Map.empty)
