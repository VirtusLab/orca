package orca.runner

import orca.OrcaDir
import orca.progress.ProgressStore
import orca.testkit.{GitRepo, TempDirs}
import orca.tools.Worktrees
import ox.discard

class WorktreeRunTest extends munit.FunSuite:

  test("the worktree lives under .orca/worktrees, keyed by the task text"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    assertEquals(path / os.up, OrcaDir.worktreesPath(repo))
    assertNotEquals(path, resolved(repo, "task B"))
    // Same key as the progress log inside it: that coupling is what makes a
    // resumed run land where its log is.
    assert(
      ProgressStore.default(path, "task A").path.last.contains(path.last),
      path.last
    )

  test("re-resolving the same task text reuses the worktree already there"):
    val repo = GitRepo.seeded()
    val first = resolved(repo, "task A")
    assertEquals(resolved(repo, "task A"), first)
    // The main checkout plus exactly one worktree: no second one was created.
    assertEquals(Worktrees.list(repo), List(repo, first))

  test("resolving from inside the worktree lands on that same worktree"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    assertEquals(WorktreeRun.resolve(path, "task A"), Right(path))

  test("the ignore marker is written before the worktree is created"):
    val repo = GitRepo.empty()
    // Creation cannot succeed on an unborn HEAD, so the marker is on disk only
    // if it was written first.
    assert(WorktreeRun.resolve(repo, "task A").isLeft)
    assert(os.exists(OrcaDir.worktreesPath(repo) / ".gitignore"))

  test("reuse puts the ignore marker back when it went missing"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    // What `git clean -xdf` in the main checkout removes: the marker only, the
    // worktree itself skipped as a repository.
    os.remove(OrcaDir.worktreesPath(repo) / ".gitignore")
    assertEquals(WorktreeRun.resolve(repo, "task A"), Right(path))
    assert(os.exists(OrcaDir.worktreesPath(repo) / ".gitignore"))

  test("a worktree whose directory was deleted is recreated"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    // What `git clean -xdff` leaves: the directory gone, git's administrative
    // entry still listing it.
    os.remove.all(path)
    assertEquals(WorktreeRun.resolve(repo, "task A"), Right(path))
    assert(os.exists(path / ".git"))

  test("recreating one worktree leaves the repository's others registered"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    val unrelated = repo / "unrelated"
    assertEquals(Worktrees.add(repo, unrelated), Right(()))
    // Both directories unreachable — a stale entry orca does not own must
    // survive the reclaim of the one it does.
    os.remove.all(path)
    os.remove.all(unrelated)
    assertEquals(WorktreeRun.resolve(repo, "task A"), Right(path))
    assert(Worktrees.list(repo).contains(unrelated))

  test("a plain directory at the path is refused, not taken over"):
    val repo = GitRepo.seeded()
    val path = resolved(repo, "task A")
    git(repo, "worktree", "remove", "--force", path.toString)
    os.makeDir.all(path)
    val refusal = WorktreeRun
      .resolve(repo, "task A")
      .left
      .getOrElse(fail("expected a refusal"))
    assert(refusal.contains(path.toString), refusal)

  test("outside a git repository, orca's own wording is used"):
    assertEquals(
      WorktreeRun.resolve(TempDirs.dir(), "task A"),
      Left(FlowLifecycle.noCommitsMessage)
    )

  test("a repository without commits gets orca's wording, not git's"):
    assertEquals(
      WorktreeRun.resolve(GitRepo.empty(), "task A"),
      Left(FlowLifecycle.noCommitsMessage)
    )

  private def resolved(repo: os.Path, userPrompt: String): os.Path =
    WorktreeRun
      .resolve(repo, userPrompt)
      .getOrElse(fail("expected a resolved worktree"))

  private def git(cwd: os.Path, args: String*): Unit =
    os.proc("git" +: args).call(cwd = cwd).discard
