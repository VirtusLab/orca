package orca.tools

import orca.subprocess.QuietProc

import scala.util.control.NonFatal

/** Why [[Worktrees.add]] refused. `NoCommitsYet` is a case of its own because
  * the caller reports it in orca's own words; for anything else git's message
  * is the best available explanation.
  */
private[orca] enum WorktreeAddFailure:
  case NoCommitsYet
  case GitFailed(message: String)

/** Git worktree plumbing for run-level isolation (`--worktree`).
  *
  * Deliberately not on [[GitTool]]: that trait is flow-facing, and a flow's
  * working directory is fixed before its body runs, so no flow can use these.
  * They run during startup, before any tool exists.
  *
  * The two reads answer "not known" (`None` / empty) rather than failing, so a
  * caller outside a git repository — or one scanning on every menu redraw — has
  * nothing to handle. [[add]] is a write, so its failure is returned.
  */
private[orca] object Worktrees:

  private val WorktreeLine = "worktree (.*)".r

  /** The main checkout of the repository containing `cwd`, identical whether
    * `cwd` is that checkout or any linked worktree of it. `None` when `cwd` is
    * not inside a git working tree.
    *
    * The checkout is read as `--show-toplevel`, not as the git directory's
    * parent: the two differ in a submodule (git dir under the superproject's
    * `.git/modules/`) and in a repository created with `--separate-git-dir`,
    * where deriving the parent would put orca's worktrees inside someone's
    * `.git`. Comparing `--git-dir` with `--git-common-dir` is what tells a
    * linked worktree from the main one; only there is git's own worktree list
    * consulted, which derives the main worktree the same parent-assuming way.
    */
  def mainCheckout(cwd: os.Path): Option[os.Path] =
    val query =
      Seq("rev-parse", "--path-format=absolute") ++
        Seq("--git-dir", "--git-common-dir", "--show-toplevel")
    probe(cwd, query*)
      .map(_.linesIterator.toList)
      .flatMap:
        case List(gitDir, commonDir, topLevel) =>
          if gitDir == commonDir then absolute(topLevel)
          else list(cwd).headOption
        // Any other shape means git did not answer what was asked — a git older
        // than 2.31 echoes the unknown `--path-format` and exits 0.
        case _ => None

  /** Paths of every worktree of the repository containing `cwd`, the main
    * checkout first; empty when `cwd` is not in a git repository. A path may
    * not exist on disk — git keeps the administrative entry of a worktree whose
    * directory was deleted, and reports it as prunable.
    */
  def list(cwd: os.Path): List[os.Path] =
    probe(cwd, "worktree", "list", "--porcelain").toList
      .flatMap(_.linesIterator)
      .flatMap:
        case WorktreeLine(path) => absolute(path)
        case _                  => None

  /** Create a worktree at `path`, detached at `cwd`'s current HEAD.
    *
    * No commit-ish is passed: git then detaches at the invoking checkout's
    * HEAD, which is the wanted start point. No `--` separator either: `path` is
    * absolute, so git cannot read it as an option.
    *
    * `--force` reclaims a path whose administrative entry outlived its
    * directory — what `git clean -xdff` in the main checkout leaves — and only
    * that: it still refuses a path that exists, and the other safeguard it
    * lifts, a branch already checked out elsewhere, cannot apply without a
    * commit-ish. Path-scoped, unlike `git worktree prune`, which would drop
    * every stale entry in the repository, including worktrees orca never made.
    *
    * Callers establish the repository with [[mainCheckout]] first, so a HEAD
    * that does not resolve here means a repository without commits rather than
    * no repository — asked as its own question, so neither side has to
    * recognise git's wording for it.
    */
  def add(cwd: os.Path, path: os.Path): Either[WorktreeAddFailure, Unit] =
    if probe(cwd, "rev-parse", "--verify", "--quiet", "HEAD").isEmpty then
      Left(WorktreeAddFailure.NoCommitsYet)
    else
      val result =
        git(cwd, "worktree", "add", "--force", "--detach", path.toString)
      if result.exitCode == 0 then Right(())
      else
        val stderr = result.err.text().trim
        Left(
          WorktreeAddFailure.GitFailed(
            if stderr.nonEmpty then stderr
            else s"git worktree add exited ${result.exitCode}"
          )
        )

  /** Git prints absolute paths for both reads above; anything else means the
    * command did not do what was asked, which is "not known" rather than a
    * failure to raise at a caller that has nowhere to put one — `os.Path`
    * throws on a relative or empty string.
    */
  private def absolute(path: String): Option[os.Path] =
    Option.when(path.startsWith("/"))(os.Path(path))

  private def git(cwd: os.Path, args: String*): os.CommandResult =
    QuietProc.call("git" +: args, cwd = cwd, env = OsGitTool.nonInteractiveEnv)

  /** Stdout of a read-only git command that exited 0; `None` on anything else,
    * including a subprocess that could not be started.
    */
  private def probe(cwd: os.Path, args: String*): Option[String] =
    try
      val result = git(cwd, args*)
      if result.exitCode == 0 then Some(result.out.text()) else None
    catch case NonFatal(_) => None
