package orca.runner

import orca.progress.ProgressStore
import orca.tools.PrHandle

/** What success teardown knows about a PR this run opened, read back from the
  * progress log.
  *
  * A log that does not load is [[Unknown]], never [[NotOpened]]: "no PR" lets
  * the throwaway auto-delete remove the branch, and a branch a PR is open
  * against cannot be recovered, while keeping one nothing points at costs a
  * stale branch.
  */
private[runner] enum OpenedPr:
  case Opened(pr: PrHandle)
  case NotOpened
  case Unknown

  /** The handle for [[BranchHandoff.of]]. `None` under [[Unknown]] too, which
    * leaves HEAD where the run ended rather than moving the user.
    */
  def handle: Option[PrHandle] = this match
    case Opened(pr) => Some(pr)
    case _          => None

  /** Whether the throwaway auto-delete may run at all: only a log that loaded
    * and records no PR permits it.
    */
  def allowsBranchDelete: Boolean = this == OpenedPr.NotOpened

private[runner] object OpenedPr:
  def from(result: ProgressStore.LoadResult): OpenedPr =
    result match
      case ProgressStore.LoadResult.Loaded(log) =>
        log.openedPr.map(Opened(_)).getOrElse(NotOpened)
      case _ => Unknown
