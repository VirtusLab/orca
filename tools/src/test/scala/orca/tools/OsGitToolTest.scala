package orca.tools

import orca.events.{OrcaEvent, OrcaListener}

import ox.either.orThrow
import java.util.concurrent.atomic.AtomicReference

class OsGitToolTest extends munit.FunSuite:

  private def withRepo(body: (OsGitTool, os.Path) => Unit): Unit =
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    body(new OsGitTool(dir), dir)

  /** Variant that captures the events the tool emits. */
  private def withRepoCapturingEvents(
      body: (OsGitTool, os.Path, AtomicReference[List[OrcaEvent]]) => Unit
  ): Unit =
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = (e: OrcaEvent) =>
      val _ = seen.updateAndGet(e :: _)
    body(new OsGitTool(dir, listener), dir, seen)

  test("createBranch switches to the new branch"):
    withRepo: (git, dir) =>
      // Need at least one commit before creating a branch (to have HEAD).
      os.write(dir / "initial.txt", "seed")
      git.commit("initial").orThrow
      git.createBranch("feature/x").orThrow
      assertEquals(git.currentBranch(), "feature/x")

  test("checkout switches to an existing branch"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "a")
      git.commit("add a").orThrow
      git.createBranch("feature/y").orThrow
      git.checkout("main").orThrow
      assertEquals(git.currentBranch(), "main")

  test(
    "createBranch returns Left(BranchAlreadyExists) when the branch is taken"
  ):
    withRepo: (git, dir) =>
      os.write(dir / "x.txt", "x")
      git.commit("seed").orThrow
      git.createBranch("dup").orThrow
      git.checkout("main").orThrow
      assert(
        git.createBranch("dup").left.exists(_.isInstanceOf[BranchAlreadyExists])
      )

  test("checkout returns Left(BranchNotFound) when the branch doesn't exist"):
    withRepo: (git, _) =>
      assert(git.checkout("ghost").left.exists(_.isInstanceOf[BranchNotFound]))

  test("commit stages all changes and records the message"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "content")
      git.commit("add file").orThrow
      val entries = git.log(1)
      assertEquals(entries.size, 1)
      assertEquals(entries.head.message, "add file")

  test("diff returns the unstaged changes"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "first")
      git.commit("initial").orThrow
      os.write.over(dir / "file.txt", "second")
      val d = git.diff()
      assert(d.contains("-first"))
      assert(d.contains("+second"))

  test("log respects the limit, returns newest-first, and parses the author"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "a")
      git.commit("first").orThrow
      os.write(dir / "b.txt", "b")
      git.commit("second").orThrow
      os.write(dir / "c.txt", "c")
      git.commit("third").orThrow
      val recent = git.log(2)
      assertEquals(recent.map(_.message), List("third", "second"))
      assertEquals(recent.map(_.author).distinct, List("Test"))

  test("addWorktree creates a new branch and linked working directory"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      val wtPath = os.temp.dir() / "feature"
      val wt = git.addWorktree(wtPath, "feature/alpha").orThrow
      assertEquals(wt.branch, "feature/alpha")
      assert(os.exists(wtPath / "seed.txt"))

  test("addWorktree checks out an existing branch instead of creating"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      git.createBranch("reuse").orThrow
      git.checkout("main").orThrow
      val wtPath = os.temp.dir() / "reused"
      val wt = git.addWorktree(wtPath, "reuse").orThrow
      assertEquals(wt.branch, "reuse")
      assert(os.exists(wtPath / "seed.txt"))

  test("listWorktrees returns the main repo plus each linked worktree"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      val wtPath = os.temp.dir() / "feature"
      val _ = git.addWorktree(wtPath, "feature/beta").orThrow
      val branches = git.listWorktrees().map(_.branch).toSet
      assert(branches.contains("main"))
      assert(branches.contains("feature/beta"))

  test("removeWorktree unlinks the worktree and drops its directory"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      val wtPath = os.temp.dir() / "gone"
      val _ = git.addWorktree(wtPath, "feature/gone").orThrow
      git.removeWorktree(wtPath).orThrow
      assert(!os.exists(wtPath))
      val branches = git.listWorktrees().map(_.branch).toSet
      assert(!branches.contains("feature/gone"))

  test("checkoutOrCreate creates a missing branch"):
    withRepo: (git, dir) =>
      os.write(dir / "x.txt", "x")
      git.commit("seed").orThrow
      git.checkoutOrCreate("feature/new")
      assertEquals(git.currentBranch(), "feature/new")

  test("checkoutOrCreate switches to an existing branch"):
    withRepo: (git, dir) =>
      os.write(dir / "x.txt", "x")
      git.commit("seed").orThrow
      git.createBranch("feature/existing").orThrow
      git.checkout("main").orThrow
      git.checkoutOrCreate("feature/existing")
      assertEquals(git.currentBranch(), "feature/existing")

  test(
    "checkoutOrCreate is a no-op (no event) when already on the target branch"
  ):
    withRepoCapturingEvents: (git, dir, seen) =>
      os.write(dir / "x.txt", "x")
      git.commit("seed").orThrow
      git.createBranch("feature/here").orThrow
      val before = seen.get().size
      git.checkoutOrCreate("feature/here")
      assertEquals(seen.get().size, before, "no new events should fire")

  test("ensureClean returns false on a clean tree"):
    withRepo: (git, dir) =>
      os.write(dir / "x.txt", "x")
      git.commit("seed").orThrow
      assertEquals(git.ensureClean("test stash"), false)

  test("ensureClean stashes pending changes and emits a Step"):
    withRepoCapturingEvents: (git, dir, seen) =>
      os.write(dir / "x.txt", "initial")
      git.commit("seed").orThrow
      os.write.over(dir / "x.txt", "dirty")
      val stashed = git.ensureClean("orca: pre-flow")
      assertEquals(stashed, true)
      assertEquals(git.diff().trim, "")
      val steps =
        seen.get().reverse.collect { case orca.events.OrcaEvent.Step(msg) =>
          msg
        }
      assert(
        steps.exists(_.contains("Working tree wasn't clean")),
        s"expected a stash Step; got: $steps"
      )

  test("createBranch / commit / checkout each emit a Step event"):
    withRepoCapturingEvents: (git, dir, seen) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial seed").orThrow
      git.createBranch("feature/emit").orThrow
      git.checkout("main").orThrow

      val steps = seen.get().reverse.collect { case OrcaEvent.Step(msg) =>
        msg
      }
      assert(
        steps.exists(_.contains("Committed: initial seed")),
        s"expected commit step; got: $steps"
      )
      assert(
        steps.exists(_.contains("Switched to a new branch 'feature/emit'")),
        s"expected createBranch step; got: $steps"
      )
      assert(
        steps.exists(_ == "Switched to branch 'main'"),
        s"expected checkout step; got: $steps"
      )

  test("commit returns Left(NothingToCommit) on a clean tree"):
    withRepo: (git, dir) =>
      os.write(dir / "x.txt", "x")
      git.commit("seed").orThrow
      assert(git.commit("noop").left.exists(_.isInstanceOf[NothingToCommit]))

  test("addWorktree returns Left(WorktreeAddFailed) when the path is taken"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      val wtPath = os.temp.dir() / "occupied"
      val _ = git.addWorktree(wtPath, "feature/first").orThrow
      val again = git.addWorktree(wtPath, "feature/first")
      assert(again.left.exists(_.isInstanceOf[WorktreeAddFailed]))

  test(
    "removeWorktree returns Left(WorktreeNotFound) when the path isn't a worktree"
  ):
    withRepo: (git, _) =>
      val ghost = os.temp.dir() / "ghost"
      assert(
        git.removeWorktree(ghost).left.exists(_.isInstanceOf[WorktreeNotFound])
      )
