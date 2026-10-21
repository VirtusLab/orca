package orca.lifecycle

import orca.effect.{FileSystem, Processes}
import orca.{OrcaDir, RunKey}
import orca.progress.ProgressStore
import orca.testkit.{Effects, GitRepo, TempDirs}
import ox.discard

class WorktreeRunTest extends munit.FunSuite:

  private val worktreeRun =
    WorktreeRun(Processes.ambient, FileSystem.ambient, NoLock)

  private object NoLock extends WorktreeLock:
    def worktreeLocked[T](mainCheckout: os.Path, key: RunKey)(op: => T): T = op

  test("the worktree lives under .orca/worktrees, keyed by the prompt"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    assertEquals(path / os.up, OrcaDir.worktreesPath(repo))
    assertNotEquals(path, resolved(repo, "task B"))
    // Same key as the progress log inside it: that coupling is what makes a
    // resumed run land where its log is.
    assert(
      ProgressStore
        .default(FileSystem.ambient, path, RunKey.of("task A"))
        .path
        .last
        .contains(path.last),
      path.last
    )

  test("re-resolving the same prompt reuses the worktree already there"):
    val repo = GitRepo.seeded()
    val first = resolved(repo, "task A")
    assertEquals(resolved(repo, "task A"), first)
    // The main checkout plus exactly one worktree: no second one was created.
    assertEquals(Effects.worktrees.list(repo), List(repo, first))

  test("a new worktree is put on a branch of its own, not left detached"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    assert(Effects.worktrees.onABranch(path))

  test("resolving from inside the worktree lands on that same worktree"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    assertEquals(worktreeRun.resolve(path, RunKey.of("task A")), Right(path))

  test("the ignore marker is written before the worktree is created"):
    val repo = GitRepo.empty()
    // Creation cannot succeed on an unborn HEAD, so the marker is on disk only
    // if it was written first.
    assert(worktreeRun.resolve(repo, RunKey.of("task A")).isLeft)
    assert(os.exists(OrcaDir.worktreesPath(repo) / ".gitignore"))

  test("reuse puts the ignore marker back when it went missing"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    // What `git clean -xdf` in the main checkout removes: the marker only, the
    // worktree itself skipped as a repository.
    os.remove(OrcaDir.worktreesPath(repo) / ".gitignore").discard
    assertEquals(worktreeRun.resolve(repo, RunKey.of("task A")), Right(path))
    assert(os.exists(OrcaDir.worktreesPath(repo) / ".gitignore"))

  test("a worktree whose directory was deleted is recreated"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    // What `git clean -xdff` leaves: the directory gone, git's administrative
    // entry still listing it.
    os.remove.all(path)
    assertEquals(worktreeRun.resolve(repo, RunKey.of("task A")), Right(path))
    assert(os.exists(path / ".git"))

  test("recreating one worktree leaves the repository's others registered"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    val unrelated = repo / "unrelated"
    assertEquals(Effects.worktrees.add(repo, unrelated), Right(()))
    // Both directories unreachable — a stale entry orca does not own must
    // survive the reclaim of the one it does.
    os.remove.all(path)
    os.remove.all(unrelated)
    assertEquals(worktreeRun.resolve(repo, RunKey.of("task A")), Right(path))
    assert(Effects.worktrees.list(repo).contains(unrelated))

  test("a plain directory at the path is refused, not taken over"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    git(repo, "worktree", "remove", "--force", path.toString)
    os.makeDir.all(path)
    val refusal = refusalOf(repo)
    assert(refusal.contains(path.toString), refusal)

  test("outside a git repository, orca's own wording is used"):
    assertEquals(
      worktreeRun.resolve(TempDirs.dir(), RunKey.of("task A")),
      Left(GitPreconditions.needsRepoWithCommit)
    )

  test("a repository without commits gets orca's wording, not git's"):
    assertEquals(
      worktreeRun.resolve(GitRepo.empty(), RunKey.of("task A")),
      Left(GitPreconditions.needsRepoWithCommit)
    )

  test("a main worktree that is not a checkout is named as the reason"):
    val checkout = GitRepo.seededSeparateGitDir()
    val worktree = checkout / os.up / "wt"
    assertEquals(Effects.worktrees.add(checkout, worktree), Right(()))
    // Resolving from here finds no main checkout, and the user is told it is
    // the repository's layout — not that they have no repository.
    val refusal = refusalOf(worktree)
    assert(refusal.contains("--separate-git-dir"), refusal)

  test("a git that does not answer the query is named as the reason"):
    // rev-parse prints one path per line, so a repository path holding a
    // newline makes the answer unreadable — as does a git too old for
    // `--path-format`, which is what the user is pointed at.
    val repo = TempDirs.dir() / "two\nlines"
    os.makeDir.all(repo)
    git(repo, "init", "-b", "main")
    val refusal = refusalOf(repo)
    assert(refusal.contains("git 2.31 or newer"), refusal)

  test("reuse puts a detached worktree back on its branch"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    // How a half-created worktree looks: `add` succeeded, the branch step did
    // not.
    git(path, "checkout", "--detach", "-q")
    assert(!Effects.worktrees.onABranch(path))
    assertEquals(worktreeRun.resolve(repo, RunKey.of("task A")), Right(path))
    assert(Effects.worktrees.onABranch(path))

  test("reuse refuses when the run's branch has gained commits"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    val branch = s"orca-worktree-${path.last}"
    os.write(path / "work.txt", "work")
    git(path, "add", "-A")
    git(path, "commit", "-q", "-m", "work in the worktree")
    // Detached BEHIND the branch, so putting the run back on it would strand
    // that commit — which is what orca refuses to do.
    git(path, "checkout", "--detach", "-q", "HEAD~1")
    val refusal = refusalOf(repo)
    assert(refusal.contains(branch), refusal)
    // The remedy names the branch, which is what holds the commits. Removing
    // the worktree does not help: the next run recreates it detached and hits
    // this same refusal.
    assert(refusal.contains(s"git branch -D $branch"), refusal)

  test("isWorktreeRun recognises orca's own worktree and nothing else"):
    val repo = GitRepo.seeded()
    val ours = resolved(repo, "task A")
    val theirs = repo / "review-their-branch"
    assertEquals(Effects.worktrees.add(repo, theirs), Right(()))
    assert(worktreeRun.isWorktreeRun(ours), "orca's own worktree")
    assert(!worktreeRun.isWorktreeRun(repo), "the main checkout is not one")
    assert(!worktreeRun.isWorktreeRun(theirs), "a worktree orca did not make")
    assert(!worktreeRun.isWorktreeRun(TempDirs.dir()), "not a repository")

  private def resolved(repo: os.Path, userPrompt: String): os.Path =
    worktreeRun
      .resolve(repo, RunKey.of(userPrompt))
      .getOrElse(fail("expected a resolved worktree"))

  private def refusalOf(cwd: os.Path): String =
    worktreeRun
      .resolve(cwd, RunKey.of("task A"))
      .left
      .getOrElse(fail("expected a refusal"))

  private def git(cwd: os.Path, args: String*): Unit =
    os.proc("git" +: args).call(cwd = cwd).discard
