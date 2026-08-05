package orca.tools

import orca.WorkspaceWrite
import orca.events.{OrcaEvent, OrcaListener}
import orca.testkit.GitRepo

import ox.either.orThrow
import java.util.concurrent.atomic.AtomicReference
import orca.testkit.TempDirs

class OsGitToolTest extends munit.FunSuite:

  // Tests exercise gated git mutators directly; mint the workspace-write token
  // once for the whole suite (package `orca.tools` can reach `WorkspaceWrite.unsafe`).
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

  /** Give `dir` a bare local-path origin — no credentials needed — and push the
    * current branch so it has an upstream.
    */
  private def pushToLocalRemote(git: OsGitTool, dir: os.Path): Unit =
    val remote = TempDirs.dir() / "remote.git"
    val _ = os.proc("git", "init", "--bare", remote.toString).call(cwd = dir)
    val _ =
      os.proc("git", "remote", "add", "origin", remote.toString).call(cwd = dir)
    git.push().orThrow

  test("upstreamHas is false when the branch has no upstream"):
    withRepo: (git, dir) =>
      os.write(dir / "f.txt", "x")
      git.commit("seed").orThrow
      assert(!git.upstreamHas(dir / "f.txt"))

  test("upstreamHas is true for a file present in the upstream tree"):
    withRepo: (git, dir) =>
      os.write(dir / "f.txt", "x")
      git.commit("seed").orThrow
      pushToLocalRemote(git, dir)
      assert(git.upstreamHas(dir / "f.txt"))

  test("upstreamHas is false for a file absent from the upstream tree"):
    withRepo: (git, dir) =>
      os.write(dir / "f.txt", "x")
      git.commit("seed").orThrow
      pushToLocalRemote(git, dir)
      // Committed after the push, so it exists locally but not upstream.
      os.write(dir / "later.txt", "y")
      git.commit("later").orThrow
      assert(!git.upstreamHas(dir / "later.txt"))

  test("upstreamHas is false for a path outside the working directory"):
    withRepo: (git, dir) =>
      os.write(dir / "f.txt", "x")
      git.commit("seed").orThrow
      pushToLocalRemote(git, dir)
      assert(!git.upstreamHas(TempDirs.dir() / "outside.txt"))

  test("upstreamHas resolves paths against a subdirectory working directory"):
    withRepo: (git, dir) =>
      os.write(dir / "nested" / "f.txt", "x", createFolders = true)
      git.commit("seed").orThrow
      pushToLocalRemote(git, dir)
      // Paths are cwd-relative, so a tool rooted at `nested` must see its own
      // `f.txt` — not miss it by looking for `f.txt` at the repo root.
      val nestedGit = new OsGitTool(dir / "nested")
      assert(nestedGit.upstreamHas(dir / "nested" / "f.txt"))

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

  test("reviewDiff includes a new untracked file as a new-file diff"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write(dir / "new.txt", "brand new content")
      val diff = git.reviewDiff()
      assert(diff.contains("new.txt"), diff)
      assert(diff.contains("+brand new content"), diff)

  test("reviewDiff includes a tracked file's modification"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "first")
      git.commit("initial").orThrow
      os.write.over(dir / "file.txt", "second")
      val diff = git.reviewDiff()
      assert(diff.contains("-first"), diff)
      assert(diff.contains("+second"), diff)

  test("reviewDiff since a base commit reports work committed after it"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      val base = git.headCommit()
      os.write(dir / "committed.txt", "already committed")
      git.commit("agent committed its own work").orThrow
      assert(git.reviewDiff().isEmpty, "precondition: nothing left uncommitted")
      val diff = git.reviewDiff(base)
      assert(diff.contains("+already committed"), diff)

  test("headCommit is empty in a repository with no commits"):
    withRepo: (git, _) =>
      assertEquals(git.headCommit(), None)

  test("diffStat names the changed file and its counts"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "one\n")
      git.commit("seed").orThrow
      os.write.over(dir / "seed.txt", "two\n")
      val stat = git.diffStat()
      assert(stat.contains("seed.txt"), stat)
      assert(stat.contains("1 file changed"), stat)

  test("diffStat excludes a modified tracked .orca/ file"):
    withRepo: (git, dir) =>
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-x.json", "{}")
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write.over(dir / ".orca" / "progress-x.json", "{\"a\":1}")
      assertEquals(git.diffStat().trim, "")

  test("untrackedPaths lists new files and skips .orca/ bookkeeping"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write(dir / "new.txt", "hello")
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-x.json", "{}")
      assertEquals(git.untrackedPaths(), List("new.txt"))

  test("a tool rooted in a subdirectory still sees the whole repository"):
    // Magic pathspecs resolve against the process cwd, so a scoping mistake
    // here hides every change above the tool's working directory.
    withRepo: (_, dir) =>
      os.makeDir.all(dir / "sub")
      os.write(dir / "top.txt", "one\n")
      os.write(dir / "sub" / "inner.txt", "one\n")
      val root = new OsGitTool(dir)
      root.commit("seed").orThrow
      os.write.over(dir / "top.txt", "two\n")
      val inSub = new OsGitTool(dir / "sub")
      assert(inSub.diff().contains("top.txt"), inSub.diff())
      assert(inSub.diffStat().contains("top.txt"), inSub.diffStat())

  test("a tool rooted in a subdirectory renders new files it can reach"):
    // `git status` reports repo-root-relative paths; used unchanged they name
    // nothing from the subdirectory, and the file's contents vanish silently.
    withRepo: (_, dir) =>
      os.makeDir.all(dir / "sub")
      os.write(dir / "sub" / "seed.txt", "seed")
      val root = new OsGitTool(dir)
      root.commit("seed").orThrow
      os.write(dir / "sub" / "new.txt", "hello")
      val inSub = new OsGitTool(dir / "sub")
      assertEquals(inSub.untrackedPaths(), List("new.txt"))
      assert(inSub.reviewDiff().contains("+hello"), inSub.reviewDiff())

  test("pendingChanges reports the stat, the new files and the diff together"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "one\n")
      git.commit("seed").orThrow
      os.write.over(dir / "seed.txt", "two\n")
      os.write(dir / "new.txt", "hello\n")
      val changes = git.pendingChanges()
      assert(changes.stat.contains("seed.txt"), changes.stat)
      assertEquals(changes.newFiles, List("new.txt"))
      assert(changes.diff.contains("+two"), changes.diff)
      assert(changes.diff.contains("+hello"), changes.diff)

  test("reviewDiff excludes a modified tracked .orca/ file"):
    withRepo: (git, dir) =>
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-x.json", "{}")
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write.over(dir / ".orca" / "progress-x.json", "{\"a\":1}")
      val diff = git.reviewDiff()
      assert(!diff.contains("progress-x.json"), diff)

  test("reviewDiff excludes a new untracked .orca/ file"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-new.json", "{}")
      val diff = git.reviewDiff()
      assert(!diff.contains("progress-new.json"), diff)

  test("reviewDiff includes an untracked file inside a new directory"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.makeDir(dir / "newdir")
      os.write(dir / "newdir" / "inner.sc", "val x = 1")
      val diff = git.reviewDiff()
      assert(diff.contains("newdir/inner.sc"), diff)

  test("reviewDiff names an untracked symlink to a directory, and carries on"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.makeDir(dir / "realdir")
      os.symlink(dir / "linkdir", dir / "realdir")
      os.write(dir / "new.txt", "brand new content")
      val diff = git.reviewDiff()
      assert(diff.contains("# skipped linkdir"), diff)
      assert(diff.contains("+brand new content"), diff)

  // Guards how narrow the skip above is, not the abort it fixes: widening it to
  // every symlink would silently replace this diff with a skip line.
  test(
    "reviewDiff renders an untracked symlink to a file as a mode-120000 diff"
  ):
    withRepo: (git, dir) =>
      os.write(dir / "target.txt", "target contents")
      git.commit("seed").orThrow
      os.symlink(dir / "linkfile", dir / "target.txt")
      val diff = git.reviewDiff()
      assert(diff.contains("+++ b/linkfile"), diff)
      assert(diff.contains("new file mode 120000"), diff)

  test("reviewDiff names an untracked nested git repository, and carries on"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.makeDir(dir / "nested")
      val _ = os.proc("git", "init").call(cwd = dir / "nested")
      os.write(dir / "new.txt", "brand new content")
      val diff = git.reviewDiff()
      assert(diff.contains("# skipped nested/: nested git repository"), diff)
      assert(diff.contains("+brand new content"), diff)
      // Skipping the diff must not drop the path from what `add -A` will
      // commit: git stages a nested repo as a gitlink, so `newFiles` says so.
      assert(git.pendingChanges().newFiles.contains("nested/"))

  // Pins the probe as `os.exists`, not `os.isDir`: a linked worktree's `.git`
  // is a file, not a directory.
  test("reviewDiff names an untracked linked worktree"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      val _ =
        os.proc("git", "worktree", "add", "-q", "wt", "-b", "wtb")
          .call(cwd = dir)
      val diff = git.reviewDiff()
      assert(diff.contains("# skipped wt/: nested git repository"), diff)

  test("reviewDiff includes an untracked file whose name has spaces"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write(dir / "my new file.txt", "hello")
      val diff = git.reviewDiff()
      assert(diff.contains("my new file.txt"), diff)

  test("reviewDiff composes a tracked modification with an untracked file"):
    withRepo: (git, dir) =>
      os.write(dir / "tracked.txt", "old")
      git.commit("seed").orThrow
      os.write.over(dir / "tracked.txt", "new")
      os.write(dir / "untracked.txt", "fresh")
      val diff = git.reviewDiff()
      assert(diff.contains("-old"), diff)
      assert(diff.contains("+new"), diff)
      assert(diff.contains("untracked.txt"), diff)
      assert(diff.contains("+fresh"), diff)

  test("changedFiles names a modified binary file"):
    withRepo: (git, dir) =>
      os.write(dir / "logo.png", Array[Byte](0, 1, 2, 3))
      git.commit("seed").orThrow
      os.write.over(dir / "logo.png", Array[Byte](4, 5, 6, 7))
      assertEquals(git.changedFiles(), List("logo.png"))

  test("changedFiles names a committed 100%-similarity rename"):
    withRepo: (git, dir) =>
      os.write(dir / "Old.scala", "object A")
      git.commit("seed").orThrow
      val base = git.headCommit()
      os.move(dir / "Old.scala", dir / "New.scala")
      git.commit("rename").orThrow
      assertEquals(git.changedFiles(base), List("New.scala"))

  test("changedFiles names a modified path containing a space"):
    withRepo: (git, dir) =>
      os.write(dir / "my notes.md", "one")
      git.commit("seed").orThrow
      os.write.over(dir / "my notes.md", "two")
      assertEquals(git.changedFiles(), List("my notes.md"))

  // A tab is what separates `--numstat`'s own fields, and `-z` turns off the
  // quoting that would otherwise hide one inside a name — so a tabbed path
  // arrives looking like an extra field.
  test("changedFiles names a modified path containing a tab"):
    withRepo: (git, dir) =>
      os.write(dir / "tab\there.md", "one")
      git.commit("seed").orThrow
      os.write.over(dir / "tab\there.md", "two")
      assertEquals(git.changedFiles(), List("tab\there.md"))

  test("changedFileStats counts the lines a change added and removed"):
    withRepo: (git, dir) =>
      os.write(dir / "notes.md", "one\ntwo\n")
      git.commit("seed").orThrow
      os.write.over(dir / "notes.md", "one\ntwo\nthree\n")
      assertEquals(
        git.changedFileStats(),
        List(ChangedFile("notes.md", FileChange.Lines(1, 0)))
      )

  test("changedFileStats reports a binary change without a count"):
    withRepo: (git, dir) =>
      os.write(dir / "logo.png", Array[Byte](0, 1, 2, 3))
      git.commit("seed").orThrow
      os.write.over(dir / "logo.png", Array[Byte](4, 5, 6, 7))
      assertEquals(
        git.changedFileStats(),
        List(ChangedFile("logo.png", FileChange.Binary))
      )

  test("changedFileStats reports an untracked file as new"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write(dir / "fresh.txt", "one\ntwo\n")
      assertEquals(
        git.changedFileStats(),
        List(ChangedFile("fresh.txt", FileChange.New))
      )

  test("changedFiles from a subdirectory is unaffected by diff.relative"):
    // The setting makes git print paths relative to the subdirectory, which
    // hides changes above it and makes the workDir translation name the wrong
    // files.
    withRepo: (_, dir) =>
      os.makeDir.all(dir / "sub")
      os.write(dir / "top.txt", "one")
      os.write(dir / "sub" / "inner.txt", "one")
      new OsGitTool(dir).commit("seed").orThrow
      os.write.over(dir / "top.txt", "two")
      val _ = os.proc("git", "config", "diff.relative", "true").call(cwd = dir)
      assertEquals(
        new OsGitTool(dir / "sub").changedFiles(),
        List("../top.txt")
      )

  test("commit on a corrupted repo throws with status + fsck diagnostics"):
    // Integration check that the formatter is wired into the commit path:
    // corrupt the index so `git add -A` fails, then confirm the thrown message
    // carries the status + fsck blocks rather than the bare stderr.
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write.over(dir / ".git" / "index", "garbage")
      os.write(dir / "another.txt", "x")
      val ex = intercept[orca.OrcaFlowException](git.commit("noop"))
      assert(ex.getMessage.contains("git add -A failed"), ex.getMessage)
      assert(ex.getMessage.contains("git status --porcelain:"), ex.getMessage)
      assert(ex.getMessage.contains("git fsck --no-progress:"), ex.getMessage)
