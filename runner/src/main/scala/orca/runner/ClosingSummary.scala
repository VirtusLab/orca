package orca.runner

import orca.progress.CommitHash

/** What a run produced, measured against the commit it started from, on the
  * branch `countedOn` — the feature branch, where the work is. Absent when no
  * starting commit is recorded — a resumed run whose recorded commit is no
  * longer an ancestor of HEAD — where any count would be a guess.
  *
  * Only tracked files are counted, so the count is exactly what the `git diff`
  * this summary offers can show.
  */
private[runner] case class RunChanges(
    base: CommitHash,
    filesChanged: Int,
    countedOn: String
)

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
    *
    * When HEAD has left the branch the count was taken on (a PR flow's
    * `returnToStartBranch`), the command is a range diff and names that branch
    * — a plain `git diff <base>` would run against the branch the user landed
    * on and show none of the work.
    */
  def lines(branch: String, changes: Option[RunChanges]): List[String] =
    val where = s"done — you are on branch '$branch'"
    changes match
      case None                      => List(where)
      case Some(RunChanges(_, 0, _)) => List(where, "no files changed")
      case Some(RunChanges(base, files, countedOn)) =>
        // `countedOn` still exists whenever the range form is reached: the only
        // branch teardown deletes is a throwaway one, which by definition
        // carries no committed change against the start branch — and every
        // tracked change a stage makes is committed by that stage, so a
        // non-zero count and a throwaway branch don't co-occur.
        val target =
          if countedOn == branch then base.short
          else s"${base.short}..$countedOn"
        List(
          where,
          s"$files file(s) changed since ${base.short}",
          s"next: git diff $target"
        )
