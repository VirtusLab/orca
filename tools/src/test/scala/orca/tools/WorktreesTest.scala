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
    val checkout = GitRepo.seededSeparateGitDir()
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

  test("startBranch refuses to move a branch holding commits HEAD lacks"):
    val repo = GitRepo.seeded()
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    assertEquals(Worktrees.startBranch(path, "wt-branch"), Right(()))
    os.write(path / "work.txt", "work")
    git(path, "add", "-A").discard
    git(path, "commit", "-q", "-m", "work in the worktree").discard
    // Every removal route leaves the branch behind, so a re-run finds it.
    git(repo, "worktree", "remove", "--force", path.toString).discard
    assertEquals(Worktrees.add(repo, path), Right(()))
    assertEquals(
      Worktrees.startBranch(path, "wt-branch"),
      Left(StartBranchFailure.WouldLoseCommits("wt-branch"))
    )
    // The commit is still reachable from the branch orca refused to move.
    assert(
      gitOut(repo, "log", "-1", "--format=%s", "wt-branch")
        .contains("work in the worktree")
    )

  test("startBranch takes a freshly added worktree off its detached HEAD"):
    val repo = GitRepo.seeded()
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    assertEquals(Worktrees.headBranch(path), Some("HEAD"))
    assertEquals(Worktrees.startBranch(path, "wt-branch"), Right(()))
    assertEquals(Worktrees.headBranch(path), Some("wt-branch"))
    assertEquals(gitOut(path, "rev-parse", "--abbrev-ref", "HEAD"), "wt-branch")

  test("a worktree that cannot be read reads as not on a branch"):
    val repo = GitRepo.seeded()
    val path = repo / "wt"
    assertEquals(Worktrees.add(repo, path), Right(()))
    assertEquals(Worktrees.startBranch(path, "wt-branch"), Right(()))
    // What `git clean -xdff` leaves: git cannot be started in a directory that
    // is not there, so the branch it was on a moment ago is not an answer any
    // more. os-lib spawns from a thread of its own, so the failed exec also
    // prints a stack trace to stderr — the probe catches it, and the test log
    // shows it even while passing.
    os.remove.all(path)
    assertEquals(Worktrees.headBranch(path), None)
    assert(!Worktrees.onABranch(path), "unknown must not read as on a branch")

  test("mainCheckout never answers the git dir, even from a linked worktree"):
    val checkout = GitRepo.seededSeparateGitDir()
    val worktree = checkout / os.up / "wt"
    assertEquals(Worktrees.add(checkout, worktree), Right(()))
    // git names the git dir as the main worktree here, so there is no checkout
    // to answer with: refusing beats writing a worktree inside `.git`.
    assertEquals(Worktrees.mainCheckout(worktree), None)
    // And the reason is the layout, not "you have no repository" — the caller
    // words those two very differently.
    assertEquals(
      Worktrees.mainCheckoutOrReason(worktree),
      Left(MainCheckoutFailure.MainWorktreeNotACheckout)
    )

  test("mainCheckoutOrReason separates a non-repository from a bad layout"):
    assertEquals(
      Worktrees.mainCheckoutOrReason(TempDirs.dir()),
      Left(MainCheckoutFailure.NotARepository)
    )
    val repo = GitRepo.seeded()
    assertEquals(Worktrees.mainCheckoutOrReason(repo), Right(repo))
