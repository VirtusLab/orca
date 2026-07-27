package orca.shell.create

import scala.util.control.NonFatal

/** Commits a freshly authored Project-tier flow into the user's own repo (ADR
  * 0021 §9 amendment): a scoped `git add -- <path>` / `git commit -- <path>`
  * pathspec commit, so anything else staged or dirty in the tree is left
  * untouched. `AuthorAction.finishAuthoring` calls [[commitScoped]] right after
  * copying the authored file out of the sandbox, and folds a `false` result
  * into a "commit it yourself" hint rather than treating it as a failure — the
  * file landing on disk is the authoring result; the commit is a nicety on top
  * of it.
  */
private[shell] object FlowCommit:

  private def git(args: Seq[String], cwd: os.Path): os.CommandResult =
    os.proc("git" +: args)
      .call(
        cwd = cwd,
        stdout = os.Pipe,
        stderr = os.Pipe,
        check = false
      )

  private def isInsideWorkTree(cwd: os.Path): Boolean =
    git(Seq("rev-parse", "--is-inside-work-tree"), cwd).exitCode == 0

  /** `false` on a fresh `git init` with no commits yet ("unborn" HEAD) — the
    * pathspec commit below has no parent commit to build on in that case.
    */
  private def hasCommittedHead(cwd: os.Path): Boolean =
    git(Seq("rev-parse", "--verify", "--quiet", "HEAD"), cwd).exitCode == 0

  /** Stages and commits exactly `path` — `git add -- path` then `git commit -m
    * message -- path` — into the repo rooted at `cwd`. Returns whether the
    * commit happened; never throws. Declines (returns `false`) without running
    * any git command when `cwd` isn't inside a git work tree or HEAD is unborn,
    * and swallows any git failure the same way — a commit hiccup must never
    * fail the authoring result the caller is reporting.
    */
  def commitScoped(path: os.Path, cwd: os.Path, message: String): Boolean =
    try
      isInsideWorkTree(cwd) && hasCommittedHead(cwd) && {
        val added = git(Seq("add", "--", path.toString), cwd)
        added.exitCode == 0 &&
        git(
          Seq("commit", "-m", message, "--", path.toString),
          cwd
        ).exitCode == 0
      }
    catch case NonFatal(_) => false
