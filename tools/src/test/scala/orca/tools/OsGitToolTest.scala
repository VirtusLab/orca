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

  private def withSeededRepo(body: (OsGitTool, os.Path) => Unit): Unit =
    val dir = GitRepo.seeded()
    body(new OsGitTool(dir), dir)

  /** True when a read never reached git: the revision failed validation. */
  private def rejected(result: Either[GitReadFailed, String]): Boolean =
    result.left.exists(_.isInstanceOf[GitReadFailed.InvalidRev])

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
    withSeededRepo: (git, _) =>
      git.createBranch("feature/x").orThrow
      assertEquals(git.currentBranch(), "feature/x")

  test("checkout switches to an existing branch"):
    withSeededRepo: (git, _) =>
      git.createBranch("feature/y").orThrow
      git.checkout("main").orThrow
      assertEquals(git.currentBranch(), "main")

  test(
    "createBranch returns Left(BranchAlreadyExists) when the branch is taken"
  ):
    withSeededRepo: (git, _) =>
      git.createBranch("dup").orThrow
      git.checkout("main").orThrow
      assert(
        git.createBranch("dup").left.exists(_.isInstanceOf[BranchAlreadyExists])
      )

  test("checkout returns Left(BranchNotFound) when the branch doesn't exist"):
    withRepo: (git, _) =>
      assert(git.checkout("ghost").left.exists(_.isInstanceOf[BranchNotFound]))

  test("checkout of a dash-leading name returns Left(BranchNotFound)"):
    withRepo: (git, _) =>
      assert(git.checkout("-x").left.exists(_.isInstanceOf[BranchNotFound]))

  test("createBranch of a dash-leading name throws instead of returning Left"):
    // `branchExists` tolerates the name, but `createBranch`'s own
    // `git checkout -b` still rejects it, so there is no typed Left here.
    withSeededRepo: (git, _) =>
      val ex = intercept[orca.OrcaFlowException](git.createBranch("-x"))
      assert(ex.getMessage.contains("not a valid branch name"), ex.getMessage)

  test("isIgnored is true for a gitignored path and false otherwise"):
    withRepo: (git, dir) =>
      os.write(dir / ".gitignore", ".orca/\n")
      assert(git.isIgnored(os.sub / ".orca" / "settings.properties"))
      assert(!git.isIgnored(os.sub / "src" / "Main.scala"))

  test("isIgnored is false (not a failure) outside a git repository"):
    val dir = TempDirs.dir()
    assert(!new OsGitTool(dir).isIgnored(os.sub / "whatever.txt"))

  test("commitOnly commits exactly the given path, leaving other files out"):
    withSeededRepo: (git, dir) =>
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

  test("forceCommitOnly commits a gitignored path, leaving other files out"):
    withRepo: (git, dir) =>
      os.write(dir / ".gitignore", ".orca/\n")
      git.commit("seed").orThrow
      os.write(dir / ".orca" / "progress.json", "{}", createFolders = true)
      // Skip-branch mode reaches this call with a dirty index, so the commit
      // pathspec has to hold against a staged file as well as an untracked one.
      os.write(dir / "staged.txt", "staged")
      val _ = os.proc("git", "add", "staged.txt").call(cwd = dir)
      os.write(dir / "scratch.txt", "scratch")
      git.forceCommitOnly(dir / ".orca" / "progress.json", "orca: progress log")
      val committed = os
        .proc("git", "show", "--name-only", "--pretty=format:", "HEAD")
        .call(cwd = dir)
        .out
        .text()
        .trim
      assertEquals(committed, ".orca/progress.json")
      val status =
        os.proc("git", "status", "--porcelain").call(cwd = dir).out.text()
      assert(status.contains("?? scratch.txt"), status)
      assert(status.contains("A  staged.txt"), status)

  test("commit stages all changes and records the message"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "content")
      git.commit("add file").orThrow
      val subject =
        os.proc("git", "log", "-1", "--format=%s")
          .call(cwd = dir)
          .out
          .text()
          .trim
      assertEquals(subject, "add file")

  test("uncommittedDiff returns the unstaged changes"):
    withRepo: (git, dir) =>
      os.write(dir / "file.txt", "first")
      git.commit("initial").orThrow
      os.write.over(dir / "file.txt", "second")
      val d = git.uncommittedDiff()
      assert(d.contains("-first"))
      assert(d.contains("+second"))

  test("defaultBase falls back to origin/main when origin/HEAD is unset"):
    withSeededRepo: (git, dir) =>
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
    withSeededRepo: (git, dir) =>
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
    withSeededRepo: (git, _) =>
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

  test("ensureClean creates no stash on a clean tree"):
    withSeededRepo: (git, dir) =>
      git.ensureClean("test stash")
      val stashes = os.proc("git", "stash", "list").call(cwd = dir).out.text()
      assertEquals(stashes.trim, "")

  test("ensureClean stashes pending changes and emits a Step"):
    withRepoCapturingEvents: (git, dir, seen) =>
      os.write(dir / "x.txt", "initial")
      git.commit("seed").orThrow
      os.write.over(dir / "x.txt", "dirty")
      git.ensureClean("orca: pre-flow")
      assertEquals(git.uncommittedDiff().trim, "")
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
    withSeededRepo: (git, _) =>
      assert(git.commit("noop").left.exists(_.isInstanceOf[NothingToCommit]))

  test("push publishes the current branch to origin"):
    withSeededRepo: (git, dir) =>
      val remote = pushToLocalRemote(git, dir)
      val refs = os
        .proc("git", "for-each-ref", "--format=%(refname)")
        .call(cwd = remote)
        .out
        .text()
      assert(refs.contains("refs/heads/main"), refs)

  /** Give `dir` a bare local-path origin and push the current branch so it has
    * an upstream. A bare local path needs no credentials, so the push argv —
    * including the injected gh credential fallback, inert for a non-github
    * remote — is exercised without a network round-trip. Returns the remote.
    */
  private def pushToLocalRemote(git: OsGitTool, dir: os.Path): os.Path =
    val remote = TempDirs.dir() / "remote.git"
    val _ = os.proc("git", "init", "--bare", remote.toString).call(cwd = dir)
    val _ =
      os.proc("git", "remote", "add", "origin", remote.toString).call(cwd = dir)
    git.push().orThrow
    remote

  test("upstreamHas is false when the branch has no upstream"):
    withSeededRepo: (git, dir) =>
      assert(!git.upstreamHas(dir / "seed.txt"))

  test("upstreamHas is true for a file present in the upstream tree"):
    withSeededRepo: (git, dir) =>
      val _ = pushToLocalRemote(git, dir)
      assert(git.upstreamHas(dir / "seed.txt"))

  test("upstreamHas is false for a file absent from the upstream tree"):
    withSeededRepo: (git, dir) =>
      val _ = pushToLocalRemote(git, dir)
      // Committed after the push, so it exists locally but not upstream.
      os.write(dir / "later.txt", "y")
      git.commit("later").orThrow
      assert(!git.upstreamHas(dir / "later.txt"))

  test("upstreamHas is false for a path outside the working directory"):
    withSeededRepo: (git, dir) =>
      val _ = pushToLocalRemote(git, dir)
      assert(!git.upstreamHas(TempDirs.dir() / "outside.txt"))

  test("upstreamHas resolves paths against a subdirectory working directory"):
    withRepo: (git, dir) =>
      os.write(dir / "nested" / "f.txt", "x", createFolders = true)
      git.commit("seed").orThrow
      val _ = pushToLocalRemote(git, dir)
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

  test("gitFailureMessage keeps a stderr line that starts with `|`"):
    // A commit hook writes what it likes to git's stderr, markdown included.
    val diag = OsGitTool.GitDiagnostics(status = "", fsck = "")
    val msg =
      OsGitTool.gitFailureMessage("commit -m x", "hook says:\n| no |", diag)
    assert(msg.contains("\n| no |"), msg)

  test("gitFailureMessage shows '(clean)' / '(no issues reported)' when empty"):
    val diag = OsGitTool.GitDiagnostics(status = "", fsck = "")
    val msg = OsGitTool.gitFailureMessage("add -A", "boom", diag)
    assert(msg.contains("git add -A failed: boom"), msg)
    assert(msg.contains("(clean)"), msg)
    assert(msg.contains("(no issues reported)"), msg)

  test("deleteBranch removes an existing branch"):
    withSeededRepo: (git, dir) =>
      git.createBranch("to-delete").orThrow
      git.checkout("main").orThrow
      git.deleteBranch("to-delete")
      val result =
        os.proc("git", "branch", "--list", "to-delete").call(cwd = dir)
      assertEquals(result.out.text().trim, "")

  test("deleteBranch is a no-op for a non-existent branch"):
    withSeededRepo: (git, _) =>
      // Must not throw — best-effort.
      git.deleteBranch("ghost-branch")

  test("deleteBranch does not delete the current branch"):
    withSeededRepo: (git, _) =>
      // Attempt to delete the currently checked-out branch: must silently skip.
      git.deleteBranch("main")
      assertEquals(git.currentBranch(), "main")

  test("branchHasChangesExcludingOrca is false when only .orca/ differs"):
    withSeededRepo: (git, dir) =>
      val startBranch = git.currentBranch()
      git.createBranch("feature/orca-only").orThrow
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-abc.json", "{}")
      git.commit("orca: progress log").orThrow
      assert(
        !git.branchHasChangesExcludingOrca(startBranch, "feature/orca-only")
      )

  test("branchHasChangesExcludingOrca is true when code changes exist"):
    withSeededRepo: (git, dir) =>
      val startBranch = git.currentBranch()
      git.createBranch("feature/has-code").orThrow
      os.write(dir / "feature.txt", "new feature")
      git.commit("add feature").orThrow
      assert(git.branchHasChangesExcludingOrca(startBranch, "feature/has-code"))

  test("reviewChanges since a base commit reports work committed after it"):
    withSeededRepo: (git, dir) =>
      val base = git.headCommit()
      os.write(dir / "committed.txt", "already committed")
      git.commit("agent committed its own work").orThrow
      assert(
        git.reviewChanges().diff.isEmpty,
        "precondition: nothing left uncommitted"
      )
      val diff = git.reviewChanges(base).diff
      assert(diff.contains("+already committed"), diff)

  test("reviewChanges describes one change set as both a diff and a file list"):
    withRepo: (git, dir) =>
      os.write(dir / "tracked.txt", "first")
      git.commit("seed").orThrow
      os.write.over(dir / "tracked.txt", "second")
      os.write(dir / "new.txt", "brand new")
      val sample = git.reviewChanges()
      // Both projections come off one untracked sample, so the new file is in
      // the diff body as well as the list.
      assert(sample.diff.contains("+second"), sample.diff)
      assert(sample.diff.contains("+brand new"), sample.diff)
      assertEquals(
        sample.files.map(_.path).sorted,
        List("new.txt", "tracked.txt")
      )

  test("headCommit is empty in a repository with no commits"):
    withRepo: (git, _) =>
      assertEquals(git.headCommit(), None)

  test("pendingChanges excludes a modified tracked .orca/ file from the stat"):
    withRepo: (git, dir) =>
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-x.json", "{}")
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write.over(dir / ".orca" / "progress-x.json", "{\"a\":1}")
      assertEquals(git.pendingChanges().stat.trim, "")

  test("pendingChanges skips .orca/ bookkeeping in the new-file list"):
    withSeededRepo: (git, dir) =>
      os.write(dir / "new.txt", "hello")
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-x.json", "{}")
      assertEquals(git.pendingChanges().newFiles, List("new.txt"))

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
      assert(
        inSub.uncommittedDiff().contains("top.txt"),
        inSub.uncommittedDiff()
      )
      val stat = inSub.pendingChanges().stat
      assert(stat.contains("top.txt"), stat)

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
      assertEquals(inSub.pendingChanges().newFiles, List("new.txt"))
      val diff = inSub.reviewChanges().diff
      assert(diff.contains("+hello"), diff)

  test("pendingChanges reports the stat, the new files and the diff together"):
    withRepo: (git, dir) =>
      os.write(dir / "seed.txt", "one\n")
      git.commit("seed").orThrow
      os.write.over(dir / "seed.txt", "two\n")
      os.write(dir / "new.txt", "hello\n")
      val changes = git.pendingChanges()
      assert(changes.stat.contains("seed.txt"), changes.stat)
      assert(changes.stat.contains("1 file changed"), changes.stat)
      assertEquals(changes.newFiles, List("new.txt"))
      assert(changes.diff.contains("+two"), changes.diff)
      assert(changes.diff.contains("+hello"), changes.diff)

  test("reviewChanges excludes a modified tracked .orca/ file"):
    withRepo: (git, dir) =>
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-x.json", "{}")
      os.write(dir / "seed.txt", "seed")
      git.commit("seed").orThrow
      os.write.over(dir / ".orca" / "progress-x.json", "{\"a\":1}")
      val diff = git.reviewChanges().diff
      assert(!diff.contains("progress-x.json"), diff)

  test("reviewChanges excludes a new untracked .orca/ file"):
    withSeededRepo: (git, dir) =>
      os.makeDir(dir / ".orca")
      os.write(dir / ".orca" / "progress-new.json", "{}")
      val diff = git.reviewChanges().diff
      assert(!diff.contains("progress-new.json"), diff)

  test("reviewChanges includes an untracked file inside a new directory"):
    withSeededRepo: (git, dir) =>
      os.makeDir(dir / "newdir")
      os.write(dir / "newdir" / "inner.sc", "val x = 1")
      val diff = git.reviewChanges().diff
      assert(diff.contains("newdir/inner.sc"), diff)

  test(
    "reviewChanges names an untracked symlink to a directory, and carries on"
  ):
    withSeededRepo: (git, dir) =>
      os.makeDir(dir / "realdir")
      os.symlink(dir / "linkdir", dir / "realdir")
      os.write(dir / "new.txt", "brand new content")
      val diff = git.reviewChanges().diff
      assert(diff.contains("# skipped linkdir"), diff)
      assert(diff.contains("+brand new content"), diff)

  // Guards how narrow the skip above is, not the abort it fixes: widening it to
  // every symlink would silently replace this diff with a skip line.
  test(
    "reviewChanges renders an untracked symlink to a file as a mode-120000 diff"
  ):
    withSeededRepo: (git, dir) =>
      os.symlink(dir / "linkfile", dir / "seed.txt")
      val diff = git.reviewChanges().diff
      assert(diff.contains("+++ b/linkfile"), diff)
      assert(diff.contains("new file mode 120000"), diff)

  test(
    "reviewChanges names an untracked nested git repository, and carries on"
  ):
    withSeededRepo: (git, dir) =>
      os.makeDir(dir / "nested")
      val _ = os.proc("git", "init").call(cwd = dir / "nested")
      os.write(dir / "new.txt", "brand new content")
      val diff = git.reviewChanges().diff
      assert(diff.contains("# skipped nested/: nested git repository"), diff)
      assert(diff.contains("+brand new content"), diff)
      // Skipping the diff must not drop the path from what `add -A` will
      // commit: git stages a nested repo as a gitlink, so `newFiles` says so.
      assert(git.pendingChanges().newFiles.contains("nested/"))

  // Pins the probe as `os.exists`, not `os.isDir`: a linked worktree's `.git`
  // is a file, not a directory.
  test("reviewChanges names an untracked linked worktree"):
    withSeededRepo: (git, dir) =>
      val _ =
        os.proc("git", "worktree", "add", "-q", "wt", "-b", "wtb")
          .call(cwd = dir)
      val diff = git.reviewChanges().diff
      assert(diff.contains("# skipped wt/: nested git repository"), diff)

  test("reviewChanges includes an untracked file whose name has spaces"):
    withSeededRepo: (git, dir) =>
      os.write(dir / "my new file.txt", "hello")
      val diff = git.reviewChanges().diff
      assert(diff.contains("my new file.txt"), diff)

  test("reviewChanges composes a tracked modification with an untracked file"):
    withRepo: (git, dir) =>
      os.write(dir / "tracked.txt", "old")
      git.commit("seed").orThrow
      os.write.over(dir / "tracked.txt", "new")
      os.write(dir / "untracked.txt", "fresh")
      val diff = git.reviewChanges().diff
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

  test("reviewChanges counts the lines a change added and removed"):
    withRepo: (git, dir) =>
      os.write(dir / "notes.md", "one\ntwo\n")
      git.commit("seed").orThrow
      os.write.over(dir / "notes.md", "one\ntwo\nthree\n")
      assertEquals(
        git.reviewChanges().files,
        List(ChangedFile("notes.md", FileChange.Lines(1, 0)))
      )

  test("reviewChanges reports a binary change without a count"):
    withRepo: (git, dir) =>
      os.write(dir / "logo.png", Array[Byte](0, 1, 2, 3))
      git.commit("seed").orThrow
      os.write.over(dir / "logo.png", Array[Byte](4, 5, 6, 7))
      assertEquals(
        git.reviewChanges().files,
        List(ChangedFile("logo.png", FileChange.Binary))
      )

  test("reviewChanges reports an untracked file as new"):
    withSeededRepo: (git, dir) =>
      os.write(dir / "fresh.txt", "one\ntwo\n")
      assertEquals(
        git.reviewChanges().files,
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
    withSeededRepo: (git, dir) =>
      os.write.over(dir / ".git" / "index", "garbage")
      os.write(dir / "another.txt", "x")
      val ex = intercept[orca.OrcaFlowException](git.commit("noop"))
      assert(ex.getMessage.contains("git add -A failed"), ex.getMessage)
      assert(ex.getMessage.contains("git status --porcelain:"), ex.getMessage)
      assert(ex.getMessage.contains("git fsck --no-progress:"), ex.getMessage)

  test("show renders a commit's message and diff"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "one")
      git.commit("add a").orThrow
      val out = git.show("HEAD").orThrow
      assert(out.contains("add a"), out)
      assert(out.contains("+one"), out)

  test("show with StatOnly reports files without hunks"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "one")
      git.commit("add a").orThrow
      val out = git.show("HEAD", detail = ShowDetail.StatOnly).orThrow
      assert(out.contains("a.txt"), out)
      assert(!out.contains("+one"), out)

  test("show narrows the diff to the paths it is given"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "one")
      os.write(dir / "b.txt", "two")
      git.commit("add both").orThrow
      val out = git.show("HEAD", paths = List("a.txt")).orThrow
      assert(out.contains("a.txt"), out)
      assert(!out.contains("b.txt"), out)

  test("show refuses when no requested path is part of the commit"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "one")
      git.commit("add a").orThrow
      val failure = git.show("HEAD", paths = List("nosuch")).left.toOption.get
      assert(failure.isInstanceOf[GitReadFailed.Refused], failure)

  test("show refuses when the commit did not change a path present at the rev"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "one")
      git.commit("add a").orThrow
      os.write(dir / "b.txt", "two")
      git.commit("add b").orThrow
      val failure = git.show("HEAD", paths = List("a.txt")).left.toOption.get
      assert(failure.isInstanceOf[GitReadFailed.Refused], failure)

  test("fileAt returns a file's contents as of a commit"):
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "before")
      git.commit("first").orThrow
      val first = git.headCommit().get
      os.write.over(dir / "a.txt", "after")
      git.commit("second").orThrow
      assertEquals(git.fileAt(first, "a.txt"), Right("before"))

  test("a revision git does not know comes back as Refused, not a throw"):
    withSeededRepo: (git, _) =>
      assert(
        git
          .show("nosuchref")
          .left
          .exists(_.isInstanceOf[GitReadFailed.Refused]),
        git.show("nosuchref")
      )

  test("the revision spellings that name a commit's predecessor are accepted"):
    // `git_file_at`'s description asks the agent for a file as it was before
    // the change under review, and `git show`'s default format prints no parent
    // sha — so refusing these leaves no way to name the parent at all.
    withRepo: (git, dir) =>
      os.write(dir / "a.txt", "before")
      git.commit("first").orThrow
      os.write.over(dir / "a.txt", "after")
      git.commit("second").orThrow
      assertEquals(git.fileAt("HEAD~1", "a.txt"), Right("before"))
      assertEquals(git.fileAt("HEAD^", "a.txt"), Right("before"))
      assertEquals(git.fileAt("@", "a.txt"), Right("after"))
      // Which commit `HEAD@{1}` names is up to how the fixture moved HEAD, so
      // this asserts only that the spelling survives validation.
      assert(!rejected(git.fileAt("HEAD@{1}", "a.txt")))

  test("a revision that could be read as a flag is rejected before git runs"):
    // The rev reaches git in a revision position, so a leading dash must not
    // survive validation — `--end-of-options` is the second line of defence.
    // Spelled with characters a revision may contain, so it is the dash guard
    // being tested rather than the character class.
    withRepo: (git, _) =>
      assert(rejected(git.show("--all")))

  test("a revision carrying a character no revision may contain is rejected"):
    // The character class is the guard here: nothing about this is a range, and
    // it does not start with a dash — but a space would smuggle in a flag.
    withRepo: (git, _) =>
      assert(rejected(git.show("HEAD --output=x")))

  test("a path climbing out of the repository is rejected"):
    withRepo: (git, _) =>
      val failure = git.fileAt("HEAD", "../outside.txt").left.toOption.get
      assert(failure.isInstanceOf[GitReadFailed.InvalidPath], failure)

  test("a magic pathspec is rejected before git runs"):
    withRepo: (git, _) =>
      val failure =
        git.show("HEAD", paths = List(":(exclude)x")).left.toOption.get
      assert(failure.isInstanceOf[GitReadFailed.InvalidPath], failure)

  test("every range spelling is rejected: git show on one is unbounded"):
    // `^-` and `^@` are ranges spelled entirely with characters a revision may
    // contain: `HEAD^-` is `HEAD^..HEAD`, which on a merge is the whole merged
    // branch, and `HEAD^@` is every parent.
    withRepo: (git, _) =>
      assert(rejected(git.show("main..HEAD")))
      assert(rejected(git.show("HEAD^-")))
      assert(rejected(git.show("HEAD^@")))
      // A leading `^` excludes instead of naming, and git exits 0 having
      // printed nothing — an empty answer the agent cannot tell from a commit
      // that changed nothing.
      assert(rejected(git.show("^HEAD")))

  test("a whole-file read can never outgrow what one read holds"):
    // Above this, `fileAt`'s size check admits a file the read then cuts. The
    // cut is caught and refused, but the two limits are set independently, so
    // nothing else says they are related at all.
    assert(OsGitTool.MaxFileAtBytes <= OsGitTool.MaxReadBytes)

  test("fileAt returns a blob of exactly the limit whole"):
    withRepo: (git, dir) =>
      os.write(dir / "big.bin", "x" * OsGitTool.MaxFileAtBytes)
      git.commit("add big").orThrow
      assertEquals(
        git.fileAt("HEAD", "big.bin").map(_.length),
        Right(OsGitTool.MaxFileAtBytes)
      )

  test("fileAt refuses a blob past the whole-file limit"):
    withRepo: (git, dir) =>
      os.write(dir / "big.bin", "x" * (OsGitTool.MaxFileAtBytes + 1))
      git.commit("add big").orThrow
      val failure = git.fileAt("HEAD", "big.bin").left.toOption.get
      assert(failure.getMessage.contains("whole-file read"), failure.getMessage)

  test("show cuts a commit whose diff is past the read limit, and says so"):
    // No size query answers "how big is this commit's diff?", so the cut
    // happens as the output is read rather than before it: what git wrote past
    // the limit never reaches the heap.
    withRepo: (git, dir) =>
      os.write(
        dir / "big.txt",
        ("x" * 99 + "\n") * (OsGitTool.MaxReadBytes / 50)
      )
      git.commit("add big").orThrow
      val out = git.show("HEAD").orThrow
      assert(clue(out.length) < OsGitTool.MaxReadBytes + 100)
      assert(out.endsWith("bytes — narrow the request]"), out.takeRight(80))

  test("reviewChanges cuts an untracked file past the read limit, and says so"):
    withSeededRepo: (git, dir) =>
      os.write(
        dir / "big.txt",
        ("x" * 99 + "\n") * (OsGitTool.MaxReadBytes / 50)
      )
      val diff = git.reviewChanges().diff
      assert(clue(diff.length) < OsGitTool.MaxReadBytes + 100)
      assert(diff.endsWith(OsGitTool.CutMarker), diff.takeRight(80))

  test("reviewChanges names the untracked files past the diff budget"):
    withSeededRepo: (git, dir) =>
      // Rendered first (git lists paths sorted), and on its own it exhausts the
      // budget, so the next file is named instead of read.
      os.write(
        dir / "a-big.txt",
        ("x" * 99 + "\n") * (OsGitTool.MaxReadBytes / 50)
      )
      os.write(dir / "z-small.txt", "small")
      val diff = git.reviewChanges().diff
      assert(
        diff.contains("# skipped z-small.txt: past the"),
        diff.takeRight(200)
      )

  test("reviewChanges names an untracked file git cannot read, and carries on"):
    // The path was listed as untracked and then became unreadable — deleted by
    // a background build, or locked down as here. One such path must not abort
    // the review.
    withSeededRepo: (git, dir) =>
      os.write(dir / "locked.txt", "secret")
      os.perms.set(dir / "locked.txt", "---------")
      try
        assume(
          scala.util.Try(os.read(dir / "locked.txt")).isFailure,
          "needs a user that file permissions apply to"
        )
        val diff = git.reviewChanges().diff
        assert(diff.contains("# skipped locked.txt: git diff exited"), diff)
      finally os.perms.set(dir / "locked.txt", "rw-------")
