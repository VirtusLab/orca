package orca.shell

import orca.OrcaDir
import orca.testkit.{GitRepo, TempDirs}
import orca.tools.Worktrees

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
