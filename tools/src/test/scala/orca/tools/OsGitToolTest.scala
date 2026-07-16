package orca.tools

import orca.WorkspaceWrite
import orca.events.{OrcaEvent, OrcaListener}
import orca.testkit.GitRepo

import ox.either.orThrow
import java.util.concurrent.atomic.AtomicReference
import orca.testkit.TempDirs

class OsGitToolTest extends munit.FunSuite:

  // Tests exercise gated git mutators directly; mint the workspace-write token
  // once for the whole suite (package `orca.tools` can reach
  // `WorkspaceWrite.unsafe`).
  private given WorkspaceWrite = WorkspaceWrite.unsafe

  private def withRepo(body: (OsGitTool, os.Path) => Unit): Unit =
    val dir = GitRepo.empty()
    body(new OsGitTool(dir), dir)

  /** Variant that captures the events the tool emits. */
  private def withRepoCapturingEvents(
      body: (OsGitTool, os.Path, AtomicReference[List[OrcaEvent]]) => Unit
  ): Unit =
    val dir = GitRepo.empty()
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

  test("isIgnored is true for a gitignored path and false otherwise"):
    withRepo: (git, dir) =>
      os.write(dir / ".gitignore", ".orca/\n")
      assert(git.isIgnored(os.sub / ".orca" / "settings.properties"))
      assert(!git.isIgnored(os.sub / "src" / "Main.scala"))

  test("isIgnored is false (not a failure) outside a git repository"):
    val dir = TempDirs.dir()
    assert(!new OsGitTool(dir).isIgnored(os.sub / "whatever.txt"))

  test("add stages a normal path and leaves a gitignored path unstaged"):
    withRepo: (git, dir) =>
      os.write(dir / ".gitignore", ".orca/\n")
      git.commit("seed").orThrow
      os.write(dir / "notes.txt", "x")
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "settings.properties", "format = cargo fmt\n")
      git.add(dir / "notes.txt")
      git.add(dir / ".orca" / "settings.properties")
      val staged = os
        .proc("git", "diff", "--cached", "--name-only")
        .call(cwd = dir)
        .out
        .text()
      assert(staged.contains("notes.txt"), staged)
      assert(!staged.contains("settings.properties"), staged)

  test("commitOnly commits exactly the given path, leaving other files out"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write(dir / "settings.properties", "format = cargo fmt\n")
      // A second untracked file alongside the target: it must stay out of the
      // commit and remain untracked in the working tree.
      os.write(dir / "progress.json", "{}")
      git.commitOnly(dir / "settings.properties", "only settings")
      val committed = os
        .proc("git", "show", "--name-only", "--pretty=format:", "HEAD")
        .call(cwd = dir)
        .out
        .text()
        .trim
      assertEquals(committed, "settings.properties")
      val status =
        os.proc("git", "status", "--porcelain").call(cwd = dir).out.text()
      assert(status.contains("?? progress.json"), status)

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

  test("defaultBranch reads the remote HEAD's short name"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "x")
      git.commit("seed").orThrow
      // Point origin/HEAD at a non-main/master branch to prove it isn't
      // hard-coded: create `trunk`, set origin's symbolic ref to it.
      val _ = os
        .proc("git", "update-ref", "refs/remotes/origin/trunk", "HEAD")
        .call(cwd = dir)
      val _ = os
        .proc(
          "git",
          "symbolic-ref",
          "refs/remotes/origin/HEAD",
          "refs/remotes/origin/trunk"
        )
        .call(cwd = dir)
      assertEquals(git.defaultBranch(), Some("trunk"))

  test("defaultBranch returns None when origin/HEAD is unset"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "x")
      git.commit("seed").orThrow
      // No remote / no origin/HEAD → best-effort None.
      assertEquals(git.defaultBranch(), None)

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
      val wtPath = TempDirs.dir() / "feature"
      val wt = git.addWorktree(wtPath, "feature/alpha").orThrow
      assertEquals(wt.branch, "feature/alpha")
      assert(os.exists(wtPath / "seed.txt"))

  test("addWorktree checks out an existing branch instead of creating"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      git.createBranch("reuse").orThrow
      git.checkout("main").orThrow
      val wtPath = TempDirs.dir() / "reused"
      val wt = git.addWorktree(wtPath, "reuse").orThrow
      assertEquals(wt.branch, "reuse")
      assert(os.exists(wtPath / "seed.txt"))

  test("listWorktrees returns the main repo plus each linked worktree"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      val wtPath = TempDirs.dir() / "feature"
      val _ = git.addWorktree(wtPath, "feature/beta").orThrow
      val branches = git.listWorktrees().map(_.branch).toSet
      assert(branches.contains("main"))
      assert(branches.contains("feature/beta"))

  test("removeWorktree unlinks the worktree and drops its directory"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("initial").orThrow
      val wtPath = TempDirs.dir() / "gone"
      val _ = git.addWorktree(wtPath, "feature/gone").orThrow
      git.removeWorktree(wtPath).orThrow
      assert(!os.exists(wtPath))
      val branches = git.listWorktrees().map(_.branch).toSet
      assert(!branches.contains("feature/gone"))

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
      val wtPath = TempDirs.dir() / "occupied"
      val _ = git.addWorktree(wtPath, "feature/first").orThrow
      val again = git.addWorktree(wtPath, "feature/first")
      assert(again.left.exists(_.isInstanceOf[WorktreeAddFailed]))

  test(
    "removeWorktree returns Left(WorktreeNotFound) when the path isn't a worktree"
  ):
    withRepo: (git, _) =>
      val ghost = TempDirs.dir() / "ghost"
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
      val remote = TempDirs.dir() / "remote.git"
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

  test("deleteBranch removes an existing branch"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("seed").orThrow
      git.createBranch("to-delete").orThrow
      git.checkout("main").orThrow
      git.deleteBranch("to-delete")
      // The branch should no longer be listed.
      val result =
        os.proc("git", "branch", "--list", "to-delete").call(cwd = dir)
      assertEquals(result.out.text().trim, "")

  test("deleteBranch is a no-op for a non-existent branch"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("seed").orThrow
      // Must not throw — best-effort.
      git.deleteBranch("ghost-branch")

  test("deleteBranch does not delete the current branch"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "x")
      git.commit("seed").orThrow
      // Attempt to delete the currently checked-out branch: must silently skip.
      git.deleteBranch("main")
      assertEquals(git.currentBranch(), "main")

  test("diffBranchExcludingOrca is empty when only .orca/ differs"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      val startBranch = git.currentBranch()
      git.createBranch("feature/orca-only").orThrow
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-abc.json", "{}")
      git.commit("orca: progress log").orThrow
      val diff = git.diffBranchExcludingOrca(startBranch, "feature/orca-only")
      assert(diff.isBlank, s"expected empty diff, got: $diff")

  test("diffBranchExcludingOrca is non-empty when code changes exist"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      val startBranch = git.currentBranch()
      git.createBranch("feature/has-code").orThrow
      os.write(dir / "feature.txt", "new feature")
      git.commit("add feature").orThrow
      val diff = git.diffBranchExcludingOrca(startBranch, "feature/has-code")
      assert(!diff.isBlank, "expected non-empty diff for code changes")
      assert(
        diff.contains("feature.txt"),
        "diff should mention the changed file"
      )

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
