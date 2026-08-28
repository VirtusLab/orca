package orca.shell

import orca.OrcaDir
import orca.testkit.{GitRepo, TempDirs}
import orca.tools.Worktrees
import ox.discard

class WorktreeScanTest extends munit.FunSuite:

  test("outside a git repository the shell's own directory is the whole list"):
    val dir = TempDirs.dir()
    assertEquals(WorktreeScan.dirs(dir).all, List(dir))

  test("an orca worktree is scanned alongside the checkout"):
    val repo = GitRepo.seeded()
    val worktree = OrcaDir.ensureWorktrees(repo) / "0123456789ab"
    assertEquals(Worktrees.add(repo, worktree), Right(()))
    assertEquals(WorktreeScan.dirs(repo).all, List(repo, worktree))

  test("a worktree scans only itself, not the checkout that made it"):
    val repo = GitRepo.seeded()
    val worktree = OrcaDir.ensureWorktrees(repo) / "0123456789ab"
    assertEquals(Worktrees.add(repo, worktree), Right(()))
    // git lists the checkout and the worktree from either one; the scan is
    // scoped to the `.orca/worktrees/` of the directory it is asked about, and
    // a worktree's own is empty.
    assertEquals(WorktreeScan.dirs(worktree).all, List(worktree))

  test("a worktree does not scan its siblings"):
    val repo = GitRepo.seeded()
    val ours = OrcaDir.ensureWorktrees(repo) / "0123456789ab"
    val sibling = OrcaDir.ensureWorktrees(repo) / "ba9876543210"
    assertEquals(Worktrees.add(repo, ours), Right(()))
    assertEquals(Worktrees.add(repo, sibling), Right(()))
    assertEquals(WorktreeScan.dirs(ours).all, List(ours))
    // Both are visible from the checkout they hang off.
    assertEquals(WorktreeScan.dirs(repo).all.toSet, Set(repo, ours, sibling))

  test("a worktree orca did not create is left out"):
    val repo = GitRepo.seeded()
    val theirs = repo / "review-their-branch"
    assertEquals(Worktrees.add(repo, theirs), Right(()))
    // Its branch's committed progress log would otherwise become a resume
    // offer, handing that branch's task text to an agent.
    assertEquals(WorktreeScan.dirs(repo).all, List(repo))

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
      WorktreeScan.dirs(repo).all,
      List(repo, worktrees(2), worktrees(1), worktrees(0))
    )

  test("only the most recently used worktrees are scanned, up to the cap"):
    val repo = GitRepo.seeded()
    val worktrees = (0 to WorktreeScan.MaxScannedWorktrees).toList.map: i =>
      val path = OrcaDir.ensureWorktrees(repo) / f"wt$i%012d"
      assertEquals(Worktrees.add(repo, path), Right(()))
      os.makeDir.all(OrcaDir.runsPath(path))
      // Ascending mtimes, so index 0 is the least recently used.
      os.mtime.set(OrcaDir.runsPath(path), 1000L + i * 1000L).discard
      path
    val scanned = WorktreeScan.dirs(repo)
    assertEquals(scanned.worktrees.size, WorktreeScan.MaxScannedWorktrees)
    // The one dropped is the oldest, and the newest is kept and comes first.
    assert(!scanned.worktrees.contains(worktrees.head), "oldest must drop out")
    assertEquals(scanned.worktrees.head, worktrees.last)
