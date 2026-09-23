package orca.runner

import orca.tools.{UncommittedSnapshot, UntrackedFiles}

/** What the working tree held when the body started, which decides what failure
  * teardown may delete and what it puts back.
  */
private[orca] enum StartingTree:
  /** Clean, or stashed clean by setup: every untracked file is the run's. */
  case Clean

  /** Setup left the user's uncommitted files in place (a fresh run under
    * `--skip-branch`, `--keep-changes`, or an interactive keep answer).
    * `tracked` holds their tracked changes, when there are any.
    */
  case Kept(tracked: Option[UncommittedSnapshot])

  /** Orca cannot tell kept untracked files from the run's, so it deletes them
    * only from a clean start.
    */
  def untracked: UntrackedFiles = this match
    case Clean   => UntrackedFiles.Remove
    case Kept(_) => UntrackedFiles.Keep
