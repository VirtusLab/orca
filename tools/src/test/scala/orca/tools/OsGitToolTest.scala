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

  test("defaultBase falls back to origin/main when origin/HEAD is unset"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "x")
      git.commit("seed").orThrow
      // Simulate a freshly `git init`ed repo that pushed to an `origin/main`
      // remote without setting origin/HEAD — fake a remote-tracking ref via
      // `git update-ref` instead of pulling in a real second repo.
      val _ = os
        .proc("git", "update-ref", "refs/remotes/origin/main", "HEAD")
        .call(cwd = dir)
      assertEquals(git.defaultBase(), "origin/main")

  test("defaultBase throws when no candidate ref exists"):
    withRepo: (git, _) =>
      // No remote-tracking refs at all → none of the fallbacks resolve.
      val _ = intercept[orca.OrcaFlowException](git.defaultBase())

  test("diffVsBase returns the cumulative branch diff vs base"):
    withRepo: (git, dir) =>
      // base branch with one commit
      os.write(dir / "file.txt", "first")
      git.commit("initial").orThrow
      val baseBranch = git.currentBranch()
      // feature branch with two commits — both should appear in the diff
      git.createBranch("feature").orThrow
      os.write.over(dir / "file.txt", "second")
      git.commit("second").orThrow
      os.write(dir / "new.txt", "added")
      git.commit("third").orThrow

      val d = git.diffVsBase(baseBranch)
      assert(d.contains("-first"))
      assert(d.contains("+second"))
      assert(d.contains("+added"))

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

  test("push publishes the current branch to origin"):
    withRepo: (git, dir) =>
      os.write(dir / "f.txt", "x")
      git.commit("seed").orThrow
      // A bare local-path remote needs no credentials, so this exercises the
      // push argv — including the injected gh credential fallback, inert for a
      // non-github remote — without a network round-trip.
      val remote = os.temp.dir() / "remote.git"
      val _ = os.proc("git", "init", "--bare", remote.toString).call(cwd = dir)
      val _ = os
        .proc("git", "remote", "add", "origin", remote.toString)
        .call(cwd = dir)
      git.push().orThrow
      val refs = os
        .proc("git", "for-each-ref", "--format=%(refname)")
        .call(cwd = remote)
        .out
        .text()
      assert(refs.contains("refs/heads/main"), refs)

  test("nonInteractiveEnv disables git and ssh interactive prompts"):
    val env = OsGitTool.nonInteractiveEnv
    assertEquals(env.get("GIT_TERMINAL_PROMPT"), Some("0"))
    assert(
      env.getOrElse("GIT_SSH_COMMAND", "").contains("-o BatchMode=yes"),
      env.toString
    )

  test("isGithubRemote detects github across ssh and https forms"):
    assert(OsGitTool.isGithubRemote("git@github.com:me/repo.git"))
    assert(OsGitTool.isGithubRemote("https://github.com/me/repo.git"))
    assert(OsGitTool.isGithubRemote("ssh://git@github.com/me/repo.git"))
    assert(!OsGitTool.isGithubRemote("git@gitlab.com:me/repo.git"))
    assert(!OsGitTool.isGithubRemote("https://github.example.com/me/repo.git"))
    assert(!OsGitTool.isGithubRemote("/local/path/repo.git"))

  test("pushArgs adds no credential helper for a non-github remote"):
    assertEquals(
      OsGitTool.pushArgs(Some("git@gitlab.com:me/repo.git"), Some("tok")),
      Seq("git", "push", "-u", "origin", "HEAD")
    )

  test("pushArgs adds no credential helper when origin is unknown"):
    assertEquals(
      OsGitTool.pushArgs(None, Some("tok")),
      Seq("git", "push", "-u", "origin", "HEAD")
    )

  test("pushArgs feeds the env token directly for a github remote"):
    val args =
      OsGitTool.pushArgs(Some("git@github.com:me/repo.git"), Some("s3cr3t-tok"))
    val cred = credentialConfig(args)
    assert(cred.startsWith("credential.https://github.com.helper="), cred)
    // The helper reads the token from the environment at runtime — the literal
    // value must never appear in the argv.
    assert(cred.contains("$GITHUB_TOKEN") || cred.contains("$GH_TOKEN"), cred)
    assert(!args.exists(_.contains("s3cr3t-tok")), args.toString)

  test("pushArgs falls back to gh when a github remote has no env token"):
    val args = OsGitTool.pushArgs(Some("https://github.com/me/repo.git"), None)
    assert(credentialConfig(args).endsWith("!gh auth git-credential"))

  /** The value of the single `-c <value>` config override in a push argv. */
  private def credentialConfig(args: Seq[String]): String =
    args(args.indexOf("-c") + 1)

  test("gitFailureMessage embeds status and fsck blocks"):
    // Direct test on the formatter so we don't need to manufacture a real
    // tree-corruption failure inside a sandbox repo.
    val diag = OsGitTool.GitDiagnostics(
      status = "M  changed.txt\n?? untracked.txt",
      fsck = "missing tree fa29f13"
    )
    val msg = OsGitTool.gitFailureMessage(
      "commit -m seed",
      "fatal: unable to read tree",
      diag
    )
    assert(
      msg.contains("git commit -m seed failed: fatal: unable to read tree"),
      msg
    )
    assert(msg.contains("M  changed.txt"), msg)
    assert(msg.contains("?? untracked.txt"), msg)
    assert(msg.contains("missing tree fa29f13"), msg)

  test("gitFailureMessage shows '(clean)' / '(no issues reported)' when empty"):
    val diag = OsGitTool.GitDiagnostics(status = "", fsck = "")
    val msg = OsGitTool.gitFailureMessage("add -A", "boom", diag)
    assert(msg.contains("git add -A failed: boom"), msg)
    assert(msg.contains("(clean)"), msg)
    assert(msg.contains("(no issues reported)"), msg)

  test("commit on a corrupted repo throws with status + fsck diagnostics"):
    // Integration check that the formatter is actually wired into the commit
    // path: corrupt the index so `git add -A` fails, then confirm the thrown
    // message carries the status + fsck blocks (and isn't just the bare
    // stderr we used to throw before).
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write.over(dir / ".git" / "index", "garbage")
      os.write(dir / "another.txt", "x")
      val ex = intercept[orca.OrcaFlowException](git.commit("noop"))
      assert(ex.getMessage.contains("git add -A failed"), ex.getMessage)
      assert(ex.getMessage.contains("git status --porcelain:"), ex.getMessage)
      assert(ex.getMessage.contains("git fsck --no-progress:"), ex.getMessage)
