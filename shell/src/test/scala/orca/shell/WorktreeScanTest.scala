package orca.shell

import orca.OrcaDir
import orca.testkit.{GitRepo, TempDirs}
import orca.tools.Worktrees
import ox.discard

class WorktreeScanTest extends munit.FunSuite:

  test("outside a git repository the shell's own directory is the whole list"):
    val dir = TempDirs.dir()
    assertEquals(WorktreeScan.dirs(dir), List(dir))

  test("an orca worktree is scanned alongside the checkout"):
    val repo = GitRepo.seeded()
    val worktree = OrcaDir.ensureWorktrees(repo) / "0123456789ab"
    assertEquals(Worktrees.add(repo, worktree), Right(()))
    assertEquals(WorktreeScan.dirs(repo), List(repo, worktree))

  test("the shell's own directory is never listed twice"):
    val repo = GitRepo.seeded()
    val worktree = OrcaDir.ensureWorktrees(repo) / "0123456789ab"
    assertEquals(Worktrees.add(repo, worktree), Right(()))
    // Run from inside the worktree: git lists it too, and it must not repeat.
    assertEquals(WorktreeScan.dirs(worktree), List(worktree))

  test("a worktree orca did not create is left out"):
    val repo = GitRepo.seeded()
    val theirs = repo / "review-their-branch"
    assertEquals(Worktrees.add(repo, theirs), Right(()))
    // Its branch's committed progress log would otherwise become a resume
    // offer, handing that branch's task text to an agent.
    assertEquals(WorktreeScan.dirs(repo), List(repo))

  test("worktrees are ranked by when each last recorded a run"):
    val repo = GitRepo.seeded()
    val worktrees = List("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc").map:
      name =>
        val path = OrcaDir.ensureWorktrees(repo) / name
        assertEquals(Worktrees.add(repo, path), Right(()))
        os.makeDir.all(OrcaDir.runsPath(path))
        path
    // Newest run last-recorded wins; the shell's own checkout stays first.
    os.mtime.set(OrcaDir.runsPath(worktrees(0)), 1000L).discard
    os.mtime.set(OrcaDir.runsPath(worktrees(2)), 3000L).discard
    os.mtime.set(OrcaDir.runsPath(worktrees(1)), 2000L).discard
    assertEquals(
      WorktreeScan.dirs(repo),
      List(repo, worktrees(2), worktrees(1), worktrees(0))
    )
