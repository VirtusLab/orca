package orca.tools

import orca.OrcaFlowException

class OsGitToolTest extends munit.FunSuite:

  private def withRepo(body: (OsGitTool, os.Path) => Unit): Unit =
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    body(new OsGitTool(dir), dir)

  test("createBranch switches to the new branch"):
    withRepo: (git, dir) =>
      // Need at least one commit before creating a branch (to have HEAD).
      os.write(dir / "initial.txt", "seed")
      git.commit("initial")
      git.createBranch("feature/x")
      assertEquals(git.currentBranch(), "feature/x")

  test("checkout switches to an existing branch"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "a")
      git.commit("add a")
      git.createBranch("feature/y")
      git.checkout("main")
      assertEquals(git.currentBranch(), "main")

  test("commit stages all changes and records the message"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "content")
      git.commit("add file")
      val entries = git.log(1)
      assertEquals(entries.size, 1)
      assertEquals(entries.head.message, "add file")

  test("diff returns the unstaged changes"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "first")
      git.commit("initial")
      os.write.over(dir / "file.txt", "second")
      val d = git.diff()
      assert(d.contains("-first"))
      assert(d.contains("+second"))

  test("log respects the limit, returns newest-first, and parses the author"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "a")
      git.commit("first")
      os.write(dir / "b.txt", "b")
      git.commit("second")
      os.write(dir / "c.txt", "c")
      git.commit("third")
      val recent = git.log(2)
      assertEquals(recent.map(_.message), List("third", "second"))
      assertEquals(recent.map(_.author).distinct, List("Test"))

  test("failed git commands throw OrcaFlowException"):
    withRepo: (git, _) =>
      val _ = intercept[OrcaFlowException]:
        git.checkout("does-not-exist")

  test("addWorktree creates a new branch and linked working directory"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial")
      val wtPath = os.temp.dir() / "feature"
      val wt = git.addWorktree(wtPath, "feature/alpha")
      assertEquals(wt.branch, "feature/alpha")
      assert(os.exists(wtPath / "seed.txt"))

  test("addWorktree checks out an existing branch instead of creating"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial")
      git.createBranch("reuse")
      git.checkout("main")
      val wtPath = os.temp.dir() / "reused"
      val wt = git.addWorktree(wtPath, "reuse")
      assertEquals(wt.branch, "reuse")
      assert(os.exists(wtPath / "seed.txt"))

  test("listWorktrees returns the main repo plus each linked worktree"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial")
      val wtPath = os.temp.dir() / "feature"
      val _ = git.addWorktree(wtPath, "feature/beta")
      val branches = git.listWorktrees().map(_.branch).toSet
      assert(branches.contains("main"))
      assert(branches.contains("feature/beta"))

  test("removeWorktree unlinks the worktree and drops its directory"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial")
      val wtPath = os.temp.dir() / "gone"
      val _ = git.addWorktree(wtPath, "feature/gone")
      git.removeWorktree(wtPath)
      assert(!os.exists(wtPath))
      val branches = git.listWorktrees().map(_.branch).toSet
      assert(!branches.contains("feature/gone"))
