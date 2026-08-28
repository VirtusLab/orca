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

/** Why [[Worktrees.mainCheckoutOrReason]] could not name a main checkout. Kept
  * apart because each wants a different thing said to the user, and only the
  * command that just asked git knows which happened.
  */
private[orca] enum MainCheckoutFailure:
  /** `cwd` is not inside a git working tree — or git could not be run at all.
    */
  case NotARepository

  /** git named a main worktree that is not a checkout: it derives one by
    * stripping `/.git` from the common dir, which under `--separate-git-dir` or
    * in a submodule names the git directory itself.
    */
  case MainWorktreeNotACheckout

  /** git did not answer the query as asked — one older than 2.31 echoes the
    * unknown `--path-format` and exits 0.
    */
  case Unsupported

/** Why [[Worktrees.startBranch]] refused. */
private[orca] enum StartBranchFailure:
  /** The branch already carries commits the worktree's HEAD cannot reach, so
    * moving it there would strand them.
    */
  case WouldLoseCommits(branch: String)
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
    * not inside a git working tree, when the repository's main worktree is not
    * a checkout (`--separate-git-dir`, a submodule), and when git is too old to
    * answer — [[mainCheckoutOrReason]] tells the three apart, for the caller
    * that has to explain the failure.
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
    mainCheckoutOrReason(cwd).toOption

  def mainCheckoutOrReason(
      cwd: os.Path
  ): Either[MainCheckoutFailure, os.Path] =
    mainCheckoutOrReason(cwd, list(cwd))

  /** [[mainCheckout]] with the reason it could not answer. The three failures
    * want three different sentences, and only this command knows which one
    * happened — a caller left to re-derive it from a second question gets some
    * of them wrong.
    */
  def mainCheckoutOrReason(
      cwd: os.Path,
      worktrees: => List[os.Path]
  ): Either[MainCheckoutFailure, os.Path] =
    val query =
      Seq("rev-parse", "--path-format=absolute") ++
        Seq("--git-dir", "--git-common-dir", "--show-toplevel")
    probe(cwd, query*).map(_.linesIterator.toList) match
      case None => Left(MainCheckoutFailure.NotARepository)
      case Some(List(gitDir, commonDir, topLevel)) =>
        if gitDir == commonDir then
          absolute(topLevel).toRight(MainCheckoutFailure.Unsupported)
        // Creating worktrees under a git directory would write a checkout
        // inside the repository's own metadata store, so a candidate that is
        // not itself a checkout is refused rather than written to.
        else
          worktrees.headOption
            .filter(p => os.exists(p / ".git"))
            .toRight(MainCheckoutFailure.MainWorktreeNotACheckout)
      case Some(_) => Left(MainCheckoutFailure.Unsupported)

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
    * Detached, then [[startBranch]] — not one `git worktree add -B`: git
    * refuses to force-update a branch any worktree entry claims, stale or live
    * ("cannot force update the branch ... used by worktree at ..."), so the
    * combined form cannot recreate a worktree whose directory was deleted while
    * its entry and branch survived — the normal `git clean -xdff` case.
    *
    * No commit-ish is passed: git then detaches at the invoking checkout's
    * HEAD, which is the wanted start point. No `--` separator either: `path` is
    * absolute, so git cannot read it as an option.
    *
    * `--force` reclaims a path whose administrative entry outlived its
    * directory, and only that: it still refuses a path that exists, and the
    * other safeguard it lifts, a branch already checked out elsewhere, cannot
    * apply without a commit-ish. Path-scoped, unlike `git worktree prune`,
    * which would drop every stale entry in the repository, including worktrees
    * orca never made.
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

  /** Put `worktree` on `name`, at its current HEAD (`checkout -B`). Used on a
    * freshly [[add]]ed worktree, which git leaves detached — a state whose
    * current branch reads back as the literal "HEAD", which a run records as
    * its starting branch and resume then refuses as an unsafe ref — and on a
    * reused worktree found detached, which is that same state arrived at later.
    *
    * `-B` would reset an existing `name`, so a branch already carrying commits
    * this HEAD cannot reach is refused instead: every removal route for a
    * worktree (`git clean -xdff`, `rm -rf`, `git worktree remove`) leaves its
    * branch behind, so a re-run of the same task finds it, possibly with work
    * on it. A probe that cannot answer counts as "would lose", so an unreadable
    * repository refuses rather than resets.
    */
  def startBranch(
      worktree: os.Path,
      name: String
  ): Either[StartBranchFailure, Unit] =
    if wouldLoseCommits(worktree, name) then
      Left(StartBranchFailure.WouldLoseCommits(name))
    else
      val result = git(worktree, "checkout", "-B", name)
      if result.exitCode == 0 then Right(())
      else Left(StartBranchFailure.GitFailed(result.err.text().trim))

  /** The branch `worktree`'s HEAD is on, `Some("HEAD")` when it is detached,
    * and `None` when the question could not be answered at all — an unstartable
    * git, or a worktree directory that has gone away.
    *
    * Three-valued on purpose: callers repair a detached worktree, and reading
    * "could not answer" as "on a branch" would skip the repair for a tree whose
    * state is unknown. Same rule [[startBranch]] states for its own probe.
    */
  def headBranch(worktree: os.Path): Option[String] =
    probe(worktree, "rev-parse", "--abbrev-ref", "HEAD").map(_.trim)

  /** Whether HEAD is DEFINITELY on a branch — `false` for a detached checkout
    * and for a worktree that cannot be read, so a caller acting on it fails
    * closed.
    */
  def onABranch(worktree: os.Path): Boolean =
    headBranch(worktree).exists(_ != "HEAD")

  private def wouldLoseCommits(cwd: os.Path, branch: String): Boolean =
    val exists =
      probe(cwd, "rev-parse", "--verify", "--quiet", branch).isDefined
    exists && git(
      cwd,
      "merge-base",
      "--is-ancestor",
      branch,
      "HEAD"
    ).exitCode != 0

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
