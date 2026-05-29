package orca.plan

import orca.{FlowContext, TestFlowContext}
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.tools.{GitTool, OsGitTool}

import java.util.concurrent.atomic.AtomicReference

class PersistentPlanTest extends munit.FunSuite:

  /** Init a fresh repo with one commit and wire a FlowContext whose `git` is a
    * real `OsGitTool` rooted there. Returns the context + the dir + the
    * recorded events.
    */
  private def withRepoCtx(
      body: (FlowContext, os.Path, AtomicReference[List[OrcaEvent]]) => Unit
  ): Unit =
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = (e: OrcaEvent) =>
      val _ = seen.updateAndGet(e :: _)
    val dispatcher = new EventDispatcher(List(listener))
    val realGit = new OsGitTool(dir, listener)
    val ctx = new TestFlowContext(dispatcher):
      override lazy val git: GitTool = realGit
    body(ctx, dir, seen)

  // --- defaultPath / hashUserPrompt ---

  test("defaultPath puts the hash into '.orca/plan-<hash>.md'"):
    val expected =
      os.Path("/tmp") / ".orca" / s"plan-${Plan.hashUserPrompt("hello")}.md"
    assertEquals(Plan.defaultPath("hello", workDir = os.Path("/tmp")), expected)

  test("hashUserPrompt is stable and 12 hex chars wide"):
    val a = Plan.hashUserPrompt("the same prompt")
    val b = Plan.hashUserPrompt("the same prompt")
    assertEquals(a, b)
    assertEquals(a.length, 12)
    assert(a.forall(c => "0123456789abcdef".contains(c)), a)

  test("hashUserPrompt differs for different prompts"):
    assertNotEquals(
      Plan.hashUserPrompt("alpha"),
      Plan.hashUserPrompt("beta")
    )

  // --- recover ---

  test("recover returns None when no plan file exists"):
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      assertEquals(Plan.recover(dir / "missing.md"), None)

  test(
    "recover restores an untracked plan file that ensureClean would stash away"
  ):
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-untracked",
        description = "",
        tasks = List(Task(Title("t1"), "body"))
      )
      val planFile = dir / "untracked-plan.md"
      // Write the plan file but never commit it — the crash-before-first-
      // task-commit scenario the snapshot-restore guards against.
      os.write(planFile, Plan.render(plan))

      val recovered = Plan.recover(planFile).getOrElse(fail("expected a plan"))
      assertEquals(recovered.epicId, "feat-untracked")
      assert(
        os.exists(planFile),
        "plan file should have been restored after stash"
      )
      assertEquals(os.read(planFile), Plan.render(plan))

  test("recover parses the plan, stashes dirty changes, and switches branch"):
    withRepoCtx: (ctx, dir, seen) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-x",
        description = "",
        tasks = List(Task(Title("t1"), "body"))
      )
      val planFile = dir / "plan.md"
      os.write(planFile, Plan.render(plan))
      // Stage `plan.md` into the initial commit so it's tracked, then make a
      // dirty edit afterwards to exercise the stash path.
      val _ = os.proc("git", "add", "-A").call(cwd = dir)
      val _ = os.proc("git", "commit", "-m", "add plan").call(cwd = dir)
      os.write.over(planFile, "dirty edit")

      val recovered = Plan.recover(planFile).getOrElse(fail("expected a plan"))
      assertEquals(recovered.epicId, "feat-x")
      assertEquals(ctx.git.currentBranch(), "feat-x")
      // Stash was created — the working tree is back to the committed version.
      assertEquals(os.read(planFile), Plan.render(plan))
      val steps = seen.get().reverse.collect { case OrcaEvent.Step(m) => m }
      assert(
        steps.exists(_.contains("Working tree wasn't clean")),
        s"expected a stash Step; got: $steps"
      )
      assert(
        steps.exists(_.contains("Recovered plan")),
        s"expected a recovery Step; got: $steps"
      )

  // --- recoverOrCreate ---

  test("recoverOrCreate returns the generated plan on the create path"):
    // Pinning that the helper returns the plan from `generate` (no session
    // id — session allocation is the caller's responsibility).
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-r",
        description = "",
        tasks = List(Task(Title("t1"), "body"))
      )
      val planFile = dir / "plan.md"
      val returned = Plan.recoverOrCreate(planFile)(plan)
      assertEquals(returned, plan)
      assert(os.exists(planFile), "plan file should be persisted on create")

  test("recoverOrCreate on the recover path skips generate"):
    // Pre-existing file → `generate` must not be evaluated. Pins the
    // resume-cheaply contract.
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val existing = Plan(
        epicId = "feat-existing",
        description = "",
        tasks = List(Task(Title("t1"), "body"))
      )
      val planFile = dir / "plan.md"
      os.write(planFile, Plan.render(existing))
      val returned = Plan.recoverOrCreate(planFile):
        fail("generate must not run when the plan file exists")
      assertEquals(returned.epicId, "feat-existing")

  // --- implementTaskLoop ---

  test(
    "implementTaskLoop on an all-complete plan skips the body and only does cleanup"
  ):
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-done",
        description = "",
        tasks = List(Task(Title("t1"), "body1", completed = true))
      )
      val planFile = dir / "plan.md"
      os.write(planFile, Plan.render(plan))
      val _ = os.proc("git", "add", "-A").call(cwd = dir)
      val _ = os.proc("git", "commit", "-m", "add done plan").call(cwd = dir)

      var bodyCalls = 0
      Plan.implementTaskLoop(planFile, plan): _ =>
        bodyCalls += 1

      assertEquals(
        bodyCalls,
        0,
        "body should not run when every task is complete"
      )
      assert(!os.exists(planFile), "plan file should be removed")
      val commits = ctx.git.log(10).map(_.message)
      assertEquals(
        commits,
        List("chore: remove plan.md", "add done plan", "seed"),
        "only the cleanup commit should fire — no task commits"
      )

  test(
    "implementTaskLoop runs body per task, persists completion, commits, removes file"
  ):
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-y",
        description = "",
        tasks = List(
          Task(Title("t1"), "body1"),
          Task(Title("t2"), "body2")
        )
      )
      val planFile = dir / "plan.md"
      os.write(planFile, Plan.render(plan))
      val _ = os.proc("git", "add", "-A").call(cwd = dir)
      val _ = os.proc("git", "commit", "-m", "add plan").call(cwd = dir)

      val bodyRan = collection.mutable.ListBuffer[String]()
      Plan.implementTaskLoop(planFile, plan): task =>
        bodyRan += task.title.value
        // Simulate work the body would normally do — write a file so the
        // task commit has something to record.
        os.write(dir / s"${task.title.value}.txt", task.description)

      assertEquals(bodyRan.toList, List("t1", "t2"))
      assert(!os.exists(planFile), "plan file should be removed at the end")
      // Three commits added by implementTaskLoop: one per task + final cleanup.
      val commits = ctx.git.log(10).map(_.message)
      assertEquals(
        commits.take(3),
        List("chore: remove plan.md", "task: t2", "task: t1")
      )
      // Pin the persistComplete contract: the `task: t1` commit (HEAD~2 from
      // now, HEAD~1 from before the cleanup commit) must show t1 ticked and
      // t2 still unchecked, and the `task: t2` commit must show both ticked.
      val showT1 =
        os.proc("git", "show", "HEAD~2:plan.md").call(cwd = dir).out.text()
      val planAfterT1 = Plan.parse(showT1)
      assertEquals(planAfterT1.tasks.map(_.completed), List(true, false))
      val showT2 =
        os.proc("git", "show", "HEAD~1:plan.md").call(cwd = dir).out.text()
      val planAfterT2 = Plan.parse(showT2)
      assertEquals(planAfterT2.tasks.map(_.completed), List(true, true))

  test(
    "implementTaskLoop tolerates a no-op body (NothingToCommit is non-fatal)"
  ):
    // Regression: previously `commit().orThrow` would abort the loop on a
    // body that produced no tracked change — even though the on-disk plan
    // already had the tick written, so the next resume would skip the
    // task. Now the loop advances cleanly past a no-op body.
    withRepoCtx: (ctx, dir, seen) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-noop",
        description = "",
        tasks = List(Task(Title("t1"), "body1"), Task(Title("t2"), "body2"))
      )

      var bodyCalls = 0
      Plan.implementTaskLoop(plan): _ =>
        bodyCalls += 1
        // intentionally produce nothing the working tree would record

      assertEquals(bodyCalls, 2)
      // Each no-op task emits a Step so the silent advance is observable.
      val noOpSteps = seen.get.collect:
        case OrcaEvent.Step(m) if m.contains("produced no tracked changes") => m
      assertEquals(noOpSteps.size, 2)

  test(
    "file-backed implementTaskLoop tolerates a no-op body with a gitignored plan file"
  ):
    // Regression: the .orca/ dir is conventionally gitignored, so a no-op
    // body produces a working tree with no tracked changes — the per-task
    // `commit().orThrow` would raise NothingToCommit even though
    // `persistComplete` had already written the tick to disk. On the next
    // resume the (still-on-disk, ticked) plan file would advance past a
    // task whose body never actually committed.
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      // Mark `.orca/` as gitignored before the plan file is written, so the
      // tick goes onto disk but git ignores it. Commit the .gitignore so it
      // sticks for the rest of the test.
      os.write(dir / ".gitignore", ".orca/\n")
      val _ = os.proc("git", "add", "-A").call(cwd = dir)
      val _ = os.proc("git", "commit", "-m", "ignore .orca").call(cwd = dir)

      val plan = Plan(
        epicId = "feat-gitignored",
        description = "",
        tasks = List(Task(Title("t1"), "body1"), Task(Title("t2"), "body2"))
      )
      val planFile = dir / ".orca" / "plan.md"
      os.write(planFile, Plan.render(plan), createFolders = true)

      var bodyCalls = 0
      Plan.implementTaskLoop(planFile, plan): _ =>
        bodyCalls += 1
        // no tracked output

      // Both bodies ran — the loop didn't abort on NothingToCommit even
      // though every per-task commit had nothing to record.
      assertEquals(bodyCalls, 2)
      // No new commits (gitignored tick, no body output, swallowed cleanup
      // commit on the gitignored file removal).
      val commits = ctx.git.log(10).map(_.message)
      assertEquals(commits, List("ignore .orca", "seed"))
      // Plan file removed at end of loop.
      assert(!os.exists(planFile))

  test(
    "in-memory implementTaskLoop runs body + commits per task, no file activity"
  ):
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-mem",
        description = "",
        tasks = List(
          Task(Title("t1"), "body1"),
          Task(Title("t2"), "body2")
        )
      )

      val bodyRan = collection.mutable.ListBuffer[String]()
      Plan.implementTaskLoop(plan): task =>
        bodyRan += task.title.value
        os.write(dir / s"${task.title.value}.txt", task.description)

      assertEquals(bodyRan.toList, List("t1", "t2"))
      // No plan file is created or removed by the in-memory variant.
      assert(!os.exists(dir / ".orca"))
      // Two per-task commits; no chore-remove since there's no file.
      val commits = ctx.git.log(10).map(_.message)
      assertEquals(commits.take(3), List("task: t2", "task: t1", "seed"))
