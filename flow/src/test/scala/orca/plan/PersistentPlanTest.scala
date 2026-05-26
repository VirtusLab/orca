package orca.plan

import orca.{FlowContext, TestFlowContext}
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.llm.{BackendTag, SessionId}
import orca.tools.{GitTool, OsGitTool}

import java.util.concurrent.atomic.AtomicReference

class PersistentPlanTest extends munit.FunSuite:

  /** Init a fresh repo with one commit and wire a FlowContext whose `git` is
    * a real `OsGitTool` rooted there. Returns the context + the dir + the
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

  test("recover restores an untracked plan file that ensureClean would stash away"):
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
      assert(os.exists(planFile), "plan file should have been restored after stash")
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

  test("recoverOrCreate discards the planner's session id on the create path"):
    // The planner runs in plan mode (Plan.autonomous.from uses withReadOnly);
    // returning its session id to the caller would invite resuming it for
    // implementation, which inherits the read-only restriction. Verify both
    // branches return a fresh `llm.newSession`, not the planner-supplied id.
    withRepoCtx: (ctx, dir, _) =>
      given FlowContext = ctx
      val plan = Plan(
        epicId = "feat-r",
        description = "",
        tasks = List(Task(Title("t1"), "body"))
      )
      val plannerSid =
        SessionId[BackendTag.ClaudeCode.type]("planner-supplied-sid")
      val llm = new CannedPlanLlm(plan)
      val planFile = dir / "plan.md"
      val (sid, returned) =
        Plan.recoverOrCreate(planFile, llm)((plannerSid, plan))
      assertEquals(returned, plan)
      assertNotEquals(
        SessionId.value(sid),
        "planner-supplied-sid",
        "recoverOrCreate must mint a fresh session, not return the planner's"
      )

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

      assertEquals(bodyCalls, 0, "body should not run when every task is complete")
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
      val showT1 = os.proc("git", "show", "HEAD~2:plan.md").call(cwd = dir).out.text()
      val planAfterT1 = Plan.parse(showT1)
      assertEquals(planAfterT1.tasks.map(_.completed), List(true, false))
      val showT2 = os.proc("git", "show", "HEAD~1:plan.md").call(cwd = dir).out.text()
      val planAfterT2 = Plan.parse(showT2)
      assertEquals(planAfterT2.tasks.map(_.completed), List(true, true))