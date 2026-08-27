package orca.tools

import orca.OrcaDir
import orca.testkit.{GitRepo, TempDirs}
import ox.discard

class WorktreesTest extends munit.FunSuite:

  test("add creates a worktree that list then reports"):
    val repo = GitRepo.seeded()
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    assertEquals(Worktrees.list(repo), List(repo, path))

  test("add starts the worktree detached at the invoking checkout's HEAD"):
    val repo = GitRepo.seeded()
    git(repo, "checkout", "-q", "-b", "feature").discard
    os.write(repo / "feature.txt", "feature")
    git(repo, "add", "-A").discard
    git(repo, "commit", "-q", "-m", "feature").discard
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    // The commit only 'feature' has: a worktree started from the default
    // branch, or from anything but cwd's HEAD, would not have it.
    assertEquals(head(path), head(repo))
    assertEquals(gitOut(path, "rev-parse", "--abbrev-ref", "HEAD"), "HEAD")

  test("the .orca/worktrees marker keeps a worktree out of git add -A"):
    val repo = GitRepo.seeded()
    val path = OrcaDir.ensureWorktrees(repo) / "run"
    assertEquals(Worktrees.add(repo, path), Right(()))
    git(repo, "add", "-A").discard
    assertEquals(gitOut(repo, "status", "--porcelain"), "")

  test("mainCheckout answers the same path from the repo and its worktree"):
    val repo = GitRepo.seeded()
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    assertEquals(Worktrees.mainCheckout(repo), Some(repo))
    assertEquals(Worktrees.mainCheckout(path), Some(repo))

  test("mainCheckout answers the checkout when the git dir lives outside it"):
    val root = TempDirs.dir()
    val checkout = root / "checkout"
    os.makeDir.all(checkout)
    git(
      checkout,
      "init",
      "-q",
      "-b",
      "main",
      s"--separate-git-dir=${root / "gitdir"}"
    ).discard
    assertEquals(Worktrees.mainCheckout(checkout), Some(checkout))

  test("mainCheckout is empty outside a git repository"):
    assertEquals(Worktrees.mainCheckout(TempDirs.dir()), None)

  test("add reports an unborn HEAD as its own failure"):
    val repo = GitRepo.empty()
    assertEquals(
      Worktrees.add(repo, repo / "wt"),
      Left(WorktreeAddFailure.NoCommitsYet)
    )

  test("add carries git's message for every other failure"):
    val repo = GitRepo.seeded()
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    Worktrees.add(repo, path) match
      case Left(WorktreeAddFailure.GitFailed(message)) =>
        assert(message.nonEmpty, "git's explanation must survive")
      case other => fail(s"expected a git failure, got $other")

  private def git(cwd: os.Path, args: String*): os.CommandResult =
    os.proc("git" +: args).call(cwd = cwd)

  private def gitOut(cwd: os.Path, args: String*): String =
    git(cwd, args*).out.text().trim

  private def head(cwd: os.Path): String = gitOut(cwd, "rev-parse", "HEAD")
