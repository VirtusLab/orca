package orca.runner

import orca.progress.CommitHash

/** What a run produced, measured against the commit it started from. Absent
  * when no starting commit is recorded — a resumed run whose recorded commit is
  * no longer an ancestor of HEAD — where any count would be a guess.
  */
private[runner] case class RunChanges(base: CommitHash, filesChanged: Int)

/** The block a successful run closes with: where the work ended up, how much of
  * it there is, and the command that shows it.
  *
  * `branch` is the branch HEAD is left on, which is not always the run's
  * feature branch: a run that produced nothing has its throwaway branch deleted
  * and ends back on the branch it started from.
  */
private[runner] object ClosingSummary:
  /** One line per fact, each emitted as its own `OrcaEvent.Step` so listeners
    * that render an event per line keep them aligned.
    */
  def lines(branch: String, changes: Option[RunChanges]): List[String] =
    val where = s"done — you are on branch '$branch'"
    changes match
      case None                   => List(where)
      case Some(RunChanges(_, 0)) => List(where, "no files changed")
      case Some(RunChanges(base, files)) =>
        List(
          where,
          s"$files file(s) changed since ${base.short}",
          s"next: git diff ${base.short}"
        )
