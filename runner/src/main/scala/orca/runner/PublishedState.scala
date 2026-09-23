package orca.runner

import orca.progress.{ProgressLog, PublishedWork}
import orca.util.JsonFile

/** What success teardown knows about the work this run published, read back
  * from the progress log.
  *
  * A log that does not load is [[Unknown]], never [[NotPublished]]: "nothing
  * published" lets the throwaway auto-delete remove the branch, and the branch
  * published work was pushed from cannot be recovered, while keeping one
  * nothing points at costs a stale branch.
  */
private[runner] enum PublishedState:
  case Published(where: PublishedWork)
  case NotPublished
  case Unknown

  /** The reference for the closing summary. */
  def work: Option[PublishedWork] = this match
    case Published(w)           => Some(w)
    case NotPublished | Unknown => None

  /** `false` under [[Unknown]] too, so [[BranchHandoff]] leaves HEAD where the
    * run ended rather than moving the user on a log it cannot read.
    */
  def isPublished: Boolean = work.isDefined

  /** Whether the throwaway auto-delete may run at all. */
  def allowsBranchDelete: Boolean = this match
    case NotPublished           => true
    case Published(_) | Unknown => false

private[runner] object PublishedState:
  def from(result: JsonFile.Read[ProgressLog]): PublishedState =
    result match
      case JsonFile.Read.Loaded(log) =>
        log.published.map(Published(_)).getOrElse(NotPublished)
      case _ => Unknown
