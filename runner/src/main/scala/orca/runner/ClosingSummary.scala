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
    *
    * `worktree` is the run's own checkout when it had one (`--worktree`). The
    * user's shell never moved there, so the first line names the directory the
    * work is in instead of saying they are on that branch, and the diff is
    * scoped with `-C` so it runs from where they are actually standing.
    */
  def lines(
      branch: String,
      changes: Option[RunChanges],
      worktree: Option[os.Path]
  ): List[String] =
    // Without a worktree the sentence is about HEAD, which is `branch` by
    // definition. With one it is about where the work is, so it names the
    // branch holding it: the range case passes `countedOn`, because that case
    // exists precisely because HEAD has left it. Only that case may name a
    // branch other than HEAD's — a run with nothing to show can have had its
    // `countedOn` deleted as a throwaway by the handoff just above.
    def where(on: String): String = worktree match
      case None       => s"done — you are on branch '$branch'"
      case Some(path) => s"done — the work is in $path on branch '$on'"
    changes match
      case None => List(where(branch))
      case Some(RunChanges(_, 0, _)) =>
        List(where(branch), "no files changed")
      case Some(RunChanges(base, files, countedOn)) =>
        // `countedOn` still exists whenever the range form is reached: the only
        // branch teardown deletes is a throwaway one, which by definition
        // carries no committed change against the start branch — and every
        // tracked change a stage makes is committed by that stage, so a
        // non-zero count and a throwaway branch don't co-occur.
        val target =
          if countedOn == branch then base.short
          else s"${base.short}..$countedOn"
        // Quoted: this line is meant to be copied into a shell, and the path
        // starts with the user's own repository location, spaces and all.
        val diff =
          worktree.fold("git diff")(path => s"git -C \"$path\" diff")
        List(
          where(countedOn),
          s"$files file(s) changed since ${base.short}",
          s"next: $diff $target"
        )
