package orca.review.diff

import orca.gitref.CommitHash

/** How far back the change set a reviewer is sent reaches — what its prompt may
  * claim the diff covers.
  */
private[review] enum DiffCoverage:
  /** Everything since the enclosing stage began, committed or not; `stageBase`
    * is the stage's start commit, when known.
    */
  case Stage(stageBase: Option[CommitHash])

  /** Everything since `start`, reaching back past the current stage, committed
    * or not. Named as a concrete commit rather than "the whole run": after a
    * corrupt-log restart the recorded start is the restart's HEAD, which
    * excludes the first attempt's commits.
    */
  case Since(start: CommitHash)

  /** A diff the caller supplied: its reach is unknown, and it need not start at
    * any commit.
    */
  case Fixed

  /** The commit the diff was sampled against, when the reviewer may be told
    * one.
    */
  def base: Option[CommitHash] = this match
    case Stage(stageBase) => stageBase
    case Since(start)     => Some(start)
    case Fixed            => None
