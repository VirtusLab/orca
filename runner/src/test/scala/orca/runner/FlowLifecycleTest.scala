package orca.runner

import orca.ReportedFailure
import orca.util.RawJson
import orca.{
  BranchNamingStrategy,
  FlowContext,
  OrcaArgs,
  OrcaDir,
  RunKey,
  RunTarget,
  StagePath,
  StackSettings,
  Uncommitted,
  WorkspaceWrite,
  runFlow,
  session,
  stage,
  flow
}
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{Agent, BackendTag, ClaudeAgent, OpencodeAgent, SessionId}
import orca.gitref.{BranchName, CommitHash, Head}
import orca.progress.{
  BranchMode,
  FeatureBranch,
  FlowSource,
  ProgressHeader,
  ProgressStore,
  PublishedWork,
  StageEntry
}
import orca.sessions.SessionStore
import orca.runner.terminal.TerminalInteraction
import orca.tools.{
  GitHubAvailability,
  GitHubTool,
  OsGitTool,
  RuntimeGit,
  UntrackedFiles,
  Worktrees
}
import orca.pr.PrSummary
import ox.supervised
import ox.either.orThrow

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import orca.testkit.{
  GitRepo,
  PushlessGit,
  ScriptedBackend,
  StubGitHubTool,
  TestAgent,
  TempDirs,
  branchName,
  currentBranch,
  prHandle
}

/** Flow lifecycle tests: success/failure teardown and resume. Each uses a real
  * temp git repo (`GitRepo.seeded()`) and a null-sink `TerminalInteraction` so
  * no TTY is required.
  *
  * `flow()` calls `System.exit(1)` on body failure, so tests that must drive a
  * failing invocation use the exit-free `runFlow(...)` seam instead.
  */
class FlowLifecycleTest extends munit.FunSuite:

  /** A well-formed hash naming no commit in any test repo — what a header
    * carries when the test does not care about the whole-run diff base.
    */
  private val unreachableCommit: CommitHash = CommitHash.from("0" * 40).get

  // An absent user-global settings path: these tests drive `readSettings`
  // directly and must never read the developer's real `~/.config`.
  private val noGlobalSettings: os.Path =
    orca.testkit.TempDirs.dir() / "no-global.properties"

  test("success teardown: ends on start branch and removes progress-log file"):
    val workDir = GitRepo.seeded()
    val out = new ByteArrayOutputStream()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(out),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs("lifecycle-success"),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        summon[FlowContext].emit(OrcaEvent.Step("body ran"))
    val branch =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(branch, "main")
    val store = ProgressStore.default(workDir, RunKey.of("lifecycle-success"))
    assert(!os.exists(store.path), s"progress log ${store.path} should be gone")
    // The one call site that wires the closing block to a real listener.
    val rendered = out.toString(java.nio.charset.StandardCharsets.UTF_8)
    assert(
      rendered.contains("done — you are on branch 'main'"),
      s"the closing block must reach the terminal: $rendered"
    )

  test(
    "--worktree: the run happens in the worktree, the invoking checkout is untouched, and a re-run returns to it"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "worktree-run"
    def branchOf(dir: os.Path): String =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = dir)
        .out
        .text()
        .trim
    // Recorded from inside the body rather than asserted there: a failed
    // assertion would escape as a body failure, which `flow()` answers with
    // `System.exit(1)`.
    val ranIn = new AtomicReference[Option[os.Path]](None)
    val logSeen = new AtomicReference(false)
    val listener = new RecordingListener
    def run(): Unit =
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        flow(
          args = OrcaArgs(prompt, target = RunTarget.Worktree),
          stackSettings = Some(StackSettings.empty),
          claude = Some(_ => StubAgent.claude),
          workDir = workDir,
          extraListeners = List(listener),
          interaction = Some(interaction)
        ):
          val ctx = summon[FlowContext]
          val _ = stage("worktree-stage"):
            ranIn.set(Some(ctx.workDir))
            logSeen.set(
              os.exists(
                ProgressStore.default(ctx.workDir, RunKey.of(prompt)).path
              )
            )
            os.write.over(ctx.workDir / "made-by-the-run.txt", "worktree work")
            "ok"

    run()
    // Built from the two pieces the derivation is made of, not by calling the
    // resolver: that is a write, and the expectation must not come from the
    // code under test.
    val worktree =
      OrcaDir.worktreesPath(workDir) / RunKey.of(prompt).value
    assertEquals(ranIn.get(), Some(worktree), "the run must happen there")
    assert(logSeen.get(), "the progress log must live inside the worktree")
    // The attempt manifest is the one consumer above `runFlow`, so it is what
    // pins resolution to `flow()`: were it to move down into `runFlow`, the
    // manifest would land in the invoking checkout while everything else moved.
    assert(
      os.exists(OrcaDir.attemptsPath(worktree)),
      "the attempt manifest must be written inside the worktree"
    )
    assert(
      !os.exists(OrcaDir.attemptsPath(workDir)),
      "the invoking checkout must get no attempt manifest"
    )
    // The work is on a branch of the worktree's own — neither the detached
    // start point nor the invoking checkout's branch.
    assertNotEquals(branchOf(worktree), "HEAD")
    assertNotEquals(branchOf(worktree), "main")
    assertEquals(os.read(worktree / "made-by-the-run.txt"), "worktree work")
    // The invoking checkout never moved.
    assertEquals(branchOf(workDir), "main")
    assertEquals(
      os.proc("git", "status", "--porcelain").call(cwd = workDir).out.text(),
      ""
    )
    assert(!os.exists(workDir / "made-by-the-run.txt"))
    // The user's shell never moved, so the closing block has to say where the
    // work actually is.
    val steps = listener.events.collect:
      case OrcaEvent.Step(text) => text
    assert(
      steps.contains(
        s"done — the work is in $worktree on branch '${branchOf(worktree)}'"
      ),
      steps.mkString("\n")
    )

    ranIn.set(None)
    run()
    assertEquals(ranIn.get(), Some(worktree), "a re-run returns to it")
    assertEquals(Worktrees.list(workDir), List(workDir, worktree))

  test(
    "--worktree: a failed run in the worktree is resumable, not refused"
  ):
    val invokedIn = GitRepo.seeded()
    val prompt = "worktree-resume"
    val worktree = WorktreeRun
      .resolve(invokedIn, RunKey.of(prompt))
      .getOrElse(fail("the worktree must resolve"))
    val stageOneRuns = new AtomicInteger(0)

    val _ = intercept[ReportedFailure]:
      runFlowForTest(worktree, prompt):
        val _ = stage("stage-one"):
          stageOneRuns.incrementAndGet()
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom")

    runFlowForTest(worktree, prompt):
      val _ = stage("stage-one"):
        stageOneRuns.incrementAndGet()
        "one-done"
      val _ = stage("stage-two"):
        "two-done"
    assertEquals(stageOneRuns.get(), 1, "stage one must replay, not re-run")

  test(
    "failure teardown: stays on feature branch with clean working tree and earlier commit present"
  ):
    // Build the pre-failure state manually: feature branch with a committed
    // progress header + one completed stage entry, then a modified tracked file
    // and a newly created untracked one from a second stage. Then apply failure
    // teardown.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val prompt = "lifecycle-failure"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))

    given WorkspaceWrite = WorkspaceWrite.unsafe

    // Mirror flowSetup: create feature branch, commit progress header.
    val _ = git.createBranch(branchName("feat/lifecycle-failure"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/lifecycle-failure"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")

    // Simulate stage-one completing: write and commit code + stage entry.
    os.write(workDir / "one.txt", "content")
    store.upsertEntry(
      StageEntry(
        id = StagePath.FlowBody.child("stage-one", 0),
        resultJson = RawJson("\"done\"")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: stage-one")

    // Simulate stage-two leaving uncommitted work in both shapes a stage
    // produces: an edit to a file committed by stage one, and a brand-new file
    // — which `reset --hard` alone would leave behind.
    os.write.over(workDir / "one.txt", "partial edit")
    os.write(workDir / "two.txt", "partial")
    val featureBranch = git.currentBranch()

    // Apply failure teardown (stay on feature branch).
    git.discardUncommitted(UntrackedFiles.Remove)

    assertEquals(git.currentBranch(), featureBranch)
    val status =
      os.proc("git", "status", "--porcelain")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(
      status,
      "",
      "working tree must be clean after failure teardown"
    )
    val ids = store.load().get.entries.map(_.id)
    assert(
      ids.contains(StagePath.FlowBody.child("stage-one", 0)),
      "stage one must remain recorded"
    )
    assert(
      !os.exists(workDir / "two.txt"),
      "the new file the failed stage created must be gone"
    )
    assertEquals(os.read(workDir / "one.txt"), "content")

  test(
    "setup resume: second flow call resumes feature branch; a stage body runs only once"
  ):
    // Build the on-disk state that an aborted first run leaves: the feature
    // branch has a committed progress header + one completed stage entry, and
    // the progress-log file is present on disk (failure teardown stays on the
    // feature branch without deleting the log).
    val workDir = GitRepo.seeded()
    val prompt = "lifecycle-resume"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val invocations = new AtomicInteger(0)

    given WorkspaceWrite = WorkspaceWrite.unsafe

    val git = new OsGitTool(workDir)
    val _ = git.createBranch(branchName("feat/lifecycle-resume"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/lifecycle-resume"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = GitRepo.headCommit(workDir)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.upsertEntry(
      StageEntry(
        id = StagePath.FlowBody.child("resumable-stage", 0),
        resultJson = RawJson("\"ok\"")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: resumable-stage")

    // Second run: flow detects the header in the store, resumes the feature
    // branch (already there), and skips the already-recorded stage body.
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("resumable-stage"):
          invocations.incrementAndGet()
          "ok"

    assertEquals(
      invocations.get(),
      0,
      "body must NOT run on a resumed call where the stage is already recorded"
    )
    // Success teardown on a resumed run returns to the ORIGINAL start branch
    // recorded in the header (main), not the re-run's current feature branch.
    val branch =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(
      branch,
      "main",
      "a resumed run returns to the header's original start branch"
    )

  test(
    "runFlow propagates a body failure: stays on the feature branch with stage-one recorded"
  ):
    // End-to-end crash path: a body that completes stage 1 then THROWS in stage
    // 2. `runFlow` (exit-free) must propagate the exception; failure teardown
    // leaves us on the feature branch with stage 1's commit + log entry intact.
    val workDir = GitRepo.seeded()
    val prompt = "crash-feature"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()

    // The body failure escapes `runFlow` wrapped in `ReportedFailure` (it
    // was reported first); the original is its `cause`.
    val thrown = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        val _ = stage("stage-one"):
          os.write(workDir / "one.txt", "content")
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom in stage two")
    assertEquals(thrown.cause.getMessage, "boom in stage two")

    val branch = git.currentBranch()
    assertNotEquals(branch, startBranch)
    assertEquals(branch, store.load().get.header.branch.value)

    val ids = store.load().get.entries.map(_.id)
    assert(
      ids.contains(StagePath.FlowBody.child("stage-one", 0)),
      "stage one must be recorded"
    )
    assert(
      os.exists(workDir / "one.txt"),
      "stage one's committed file must survive failure teardown"
    )

  test(
    "runFlow resumes after a crash: stage one replays once and ends on the original start branch"
  ):
    // Two runs over the SAME repo/prompt/store. The first crashes in stage 2
    // after stage 1 runs; the second resumes — stage 1's body must NOT run again
    // (the recorded result is replayed), so the counter ends at 1, and the
    // successful second run returns to the start branch.
    val workDir = GitRepo.seeded()
    val prompt = "resume-feature"
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()
    val stageOneRuns = new AtomicInteger(0)

    // First run: crashes in stage two.
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        val _ = stage("stage-one"):
          stageOneRuns.incrementAndGet()
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom")
    assertEquals(stageOneRuns.get(), 1, "stage one runs once in the first run")

    // Failure teardown left HEAD on the feature branch; a re-run "in place"
    // finds the committed progress log there and resumes from it.
    val featureBranch = git.currentBranch()
    assertNotEquals(featureBranch, startBranch)

    // Second run from the feature branch: resumes; stage one is replayed from the
    // log (body skipped), stage two runs fresh.
    runFlowForTest(workDir, prompt):
      val _ = stage("stage-one"):
        stageOneRuns.incrementAndGet()
        "one-done"
      val _ = stage("stage-two"):
        "two-done"

    assertEquals(
      stageOneRuns.get(),
      1,
      "stage one must replay (not re-run) on resume: counter stays at 1"
    )
    assertEquals(
      git.currentBranch(),
      startBranch,
      "a successful in-place resumed run returns to the original start branch"
    )

  test(
    "runFlow resumes a per-task session loop: the task whose stage never committed re-runs onto its own recorded session"
  ):
    // Two runs over the SAME repo/prompt/store, each through its own
    // `runFlow`, so the resumed run gets a fresh FlowControl whose claimed-key
    // set starts empty. Task 2 fails AFTER minting, so its stage never commits
    // and the resume re-runs it — the only shape in which `agent.session`'s
    // reuse branch is reachable in production.
    val workDir = GitRepo.seeded()
    val prompt = "resume-session-loop"
    val sessions = SessionStore.default(workDir, RunKey.of(prompt))
    val agent = StubAgent.claude
    val tasks = List("parse the input", "wire it up", "document it")
    val failing = "wire it up"
    val bodyRuns = new AtomicInteger(0)
    val resumedIds = new AtomicReference[List[String]](Nil)

    def taskLoop(failAt: Option[String])(using
        orca.FlowContext,
        orca.FlowControl
    ): List[String] =
      for task <- tasks yield stage(s"Task: $task"):
        val _ = bodyRuns.incrementAndGet()
        val id = agent.session("implementer", seed = "brief").chat.id.value
        os.write.over(workDir / s"$task.txt", id)
        if failAt.contains(task) then throw new RuntimeException("boom")
        id

    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        val _ = taskLoop(Some(failing))
    assertEquals(bodyRuns.get(), 2, "the run stops at the failing task")
    val firstRecords = sessions.records()
    assertEquals(
      firstRecords.map(_.stage),
      List(
        StagePath.FlowBody.child(s"Task: ${tasks.head}", 0),
        StagePath.FlowBody.child(s"Task: $failing", 0)
      ),
      "the failed task's record survives the failure teardown's reset"
    )

    runFlowForTest(workDir, prompt):
      resumedIds.set(taskLoop(None))

    assertEquals(
      bodyRuns.get(),
      4,
      "the committed task replays; the failed and the unreached one run"
    )
    assertEquals(
      resumedIds.get().take(2),
      firstRecords.map(_.id),
      "the re-run task must resolve to the session IT recorded"
    )
    assert(
      !firstRecords.map(_.id).contains(resumedIds.get()(2)),
      s"the task that never ran must mint fresh; got: ${resumedIds.get()}"
    )

  test(
    "runFlow does not double-report a plain exception that already surfaced at a stage"
  ):
    // A plain RuntimeException thrown inside a stage surfaces its Error at the
    // stage boundary; the flow boundary must not report it again.
    val workDir = GitRepo.seeded()
    val prompt = "boundary-stage-once"
    val listener = new RecordingListener
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt, extraListeners = List(listener)):
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")

  test("runFlow reports a body failure outside any stage exactly once"):
    // A body that throws directly (never entering a stage) is reported once at
    // the flow boundary itself.
    val workDir = GitRepo.seeded()
    val prompt = "boundary-body-once"
    val listener = new RecordingListener
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt, extraListeners = List(listener)):
        throw new RuntimeException("boom outside any stage")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")

  test(
    "runFlow: a pre-ctx agent-factory failure escapes UNWRAPPED, not as ReportedFailure"
  ):
    // The `ReportedFailure` discriminator's other half: the `surfaced`
    // brackets only wrap lead resolution, setup and the body. A
    // per-backend agent factory (`wiring.claude`, etc.) runs eagerly inside
    // `WiredAgents.build` — called from `runFlow` BEFORE any bracket exists —
    // so its failure has no event surface to report to and must escape this
    // exit-free seam as a plain, unwrapped exception. (In production,
    // `flow()`'s backstop catches it — stderr line + `System.exit(1)` — but
    // that tail is untestable here by design: `runFlow` never exits.)
    val workDir = GitRepo.seeded()
    val prompt = "factory-boom"
    val thrown = intercept[RuntimeException]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(prompt),
            stackSettings = Some(StackSettings.empty),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = Nil,
            branchNaming = None,
            wiring = FlowWiring(claude =
              Some(_ => throw new RuntimeException("factory boom"))
            )
          )
        ):
          ()
    assertEquals(thrown.getMessage, "factory boom")
    assert(
      !thrown.isInstanceOf[ReportedFailure],
      s"a pre-ctx factory failure must NOT be wrapped in ReportedFailure: $thrown"
    )

  test(
    "R30: a log whose recorded branch differs from the current branch aborts"
  ):
    // Simulate a merged feature branch: the committed log records branch X, but
    // HEAD is on Y (as if X was merged into Y, carrying the log along). Resuming
    // must abort rather than replay against the wrong branch.
    val workDir = GitRepo.seeded()
    val prompt = "merged-hazard"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)

    given WorkspaceWrite = WorkspaceWrite.unsafe
    // Commit the log on `main` (HEAD) while it names a different feature branch.
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/merged-hazard"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val currentBranch = git.currentBranch()

    // The abort surfaces first (reported), then escapes wrapped in
    // `ReportedFailure`; the original `OrcaFlowException` is its `cause`.
    val thrown = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        val _ = stage("never-runs"):
          "x"
    assert(thrown.cause.isInstanceOf[orca.OrcaFlowException])
    val message = thrown.cause.getMessage
    assert(
      message.contains("feat/merged-hazard") &&
        message.contains(currentBranch) &&
        message.contains("merged"),
      s"abort message must name both branches and the merge hazard: $message"
    )

  test(
    "setup: a corrupt (unparseable) progress log proceeds FRESH — new branch, header written"
  ):
    // A garbage-bytes file at the store's path (a torn/truncated write, not a
    // "no log yet" absence). `setup` must take the same fresh-run path an absent
    // log would — resolve + create a branch and commit a brand-new header —
    // rather than throwing. The "starting fresh" warning routes through `emit`
    // as an `OrcaEvent.Step` so a listener (e.g. Slack) sees it.
    val workDir = GitRepo.seeded()
    val prompt = "corrupt-log-fresh"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()

    os.makeDir.all(store.path / os.up)
    os.write.over(store.path, "not json {{{", createFolders = true)

    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val setup = FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = StubAgent.claude,
      git = git,
      workDir = workDir,
      branchNaming = None,
      resolution = FlowLifecycle
        .readSettings(workDir, noGlobalSettings, Some(StackSettings.empty))
        .stack,
      flowSource = None,
      store = store,
      sessionStore = scratchSessions(),
      emit = e => { val _ = emitted.updateAndGet(e :: _) }
    )

    val steps = emitted.get().collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(_.message.contains("corrupt")),
      s"a Step warning about the corrupt log must be emitted: $steps"
    )

    // A fresh branch was resolved and created, distinct from the start branch.
    assertEquals(git.currentBranch(), setup.featureBranch.value)
    assertNotEquals(setup.featureBranch.value, startBranch)
    assertEquals(setup.startingHead, Head.OnBranch(branchName(startBranch)))
    // A brand-new header replaced the corrupt bytes: a valid fresh log, no entries.
    val loaded = store.load()
    assert(loaded.isDefined, "a fresh header must have been written")
    assertEquals(loaded.get.header.branch.value, setup.featureBranch.value)
    assertEquals(loaded.get.entries, Nil)

  test(
    "setup: a log it cannot READ aborts, naming both ways out"
  ):
    // The fresh-run path would replace the file (`os.move(replaceExisting)`
    // needs write permission on the directory, not read permission on the
    // target), silently destroying a run that may still be resumable. A
    // directory at the log's path is unreadable for any user, root included.
    val workDir = GitRepo.seeded()
    val prompt = "unreadable-log"
    val git = new OsGitTool(workDir)
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    os.makeDir.all(store.path)
    os.write(workDir / "wip.txt", "user's edit")
    val thrown = intercept[orca.OrcaFlowException]:
      val _ = FlowLifecycle.setup(
        args = OrcaArgs(prompt),
        agent = StubAgent.claude,
        git = git,
        workDir = workDir,
        branchNaming = None,
        resolution = FlowLifecycle
          .readSettings(workDir, noGlobalSettings, Some(StackSettings.empty))
          .stack,
        flowSource = None,
        store = store,
        sessionStore = scratchSessions(),
        emit = _ => ()
      )
    assert(thrown.getMessage.contains("cannot be read"), thrown.getMessage)
    assert(thrown.getMessage.contains("permissions"), thrown.getMessage)
    assert(thrown.getMessage.contains("delete the file"), thrown.getMessage)
    assertEquals(
      branchNames(workDir),
      Set("main"),
      "the abort must land before any branch is created"
    )
    assert(os.exists(workDir / "wip.txt"), "the abort must precede the stash")

  // --- refusing a fresh run on a branch another run claims (R1 amendment) ---

  /** Writes a FOREIGN progress log — a different prompt, hence a different file
    * from the run under test — whose header names `branch`, exactly as a run
    * interrupted on that branch leaves behind.
    */
  private def writeForeignLog(
      workDir: os.Path,
      branch: String,
      // `prompt` keys the log file (so two foreign logs can coexist);
      // `userPrompt` is the prompt the header records. A real run records
      // the same string for both — tests that vary one pass both.
      prompt: String = "the other task",
      userPrompt: String = "the other task",
      flow: Option[FlowSource] = Some(FlowSource.Catalog("implement.sc"))
  ): ProgressStore =
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName(branch),
        branchMode = BranchMode.Created,
        userPrompt = userPrompt,
        flow = flow,
        startingCommit = unreachableCommit
      )
    )
    store

  private def setupFresh(workDir: os.Path): FlowLifecycle.FlowSetup =
    setupForSettings(workDir, Some(StackSettings.empty), "a brand new task")

  test("setup: a repository with no commits is refused with a named next step"):
    val workDir = GitRepo.empty()
    val thrown = intercept[orca.OrcaFlowException](setupFresh(workDir): Unit)
    assert(thrown.getMessage.contains("at least one commit"), thrown.getMessage)
    assert(thrown.getMessage.contains("git commit"), thrown.getMessage)

  test(
    "setup: a FRESH run refuses to start on a branch another run's log claims"
  ):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()
    val _ = writeForeignLog(workDir, branch = startBranch)
    // Dirty on purpose: the refusal must land before the cleanliness policy,
    // so the tree is left exactly as it was — no stash, nothing swept.
    os.write.append(workDir / "seed.txt", " edited")

    val thrown = intercept[orca.OrcaFlowException](setupFresh(workDir): Unit)
    assert(
      thrown.getMessage.contains(startBranch) &&
        thrown.getMessage.contains("the other task"),
      s"the refusal must name the branch and the interrupted prompt: ${thrown.getMessage}"
    )
    // Flow name and prompt both recorded: the shell row can be offered, and
    // abandoning must be spelled as a git removal (a plain `rm` is undone by
    // the next run's stash restore, since the log is committed).
    assert(thrown.getMessage.contains("orca shell"), thrown.getMessage)
    assert(thrown.getMessage.contains("git rm"), thrown.getMessage)
    assertEquals(git.currentBranch(), startBranch)
    assertEquals(stashList(workDir), Nil)
    assert(os.read(workDir / "seed.txt").endsWith("edited"))

  test("setup: skip-branch mode refuses on a claimed branch just the same"):
    // The check sits ahead of every mode decision; skip-branch would otherwise
    // bind straight onto the branch the interrupted run is using.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    assert(git.createBranch(branchName("my-work")).isRight)
    val _ = writeForeignLog(workDir, branch = "my-work")
    val thrown = intercept[orca.OrcaFlowException]:
      val _ = FlowLifecycle.setup(
        args = OrcaArgs(
          "a brand new task",
          target = RunTarget.CurrentBranch(Uncommitted.Stash)
        ),
        agent = StubAgent.claude,
        git = git,
        workDir = workDir,
        branchNaming = None,
        resolution = FlowLifecycle
          .readSettings(workDir, noGlobalSettings, Some(StackSettings.empty))
          .stack,
        flowSource = None,
        store = ProgressStore.default(workDir, RunKey.of("a brand new task")),
        sessionStore = scratchSessions(),
        emit = _ => ()
      )
    assert(thrown.getMessage.contains("my-work"), thrown.getMessage)

  test(
    "setup: the refused run's task and flow are sanitized and clipped before they are printed"
  ):
    // The header is committed, hand-editable content: a stray escape byte must
    // not reach the terminal, a multi-line task must not break the message
    // apart, and a long one must not bury the guidance after it.
    val workDir = GitRepo.seeded()
    val startBranch = new OsGitTool(workDir).currentBranch()
    val esc = 27.toChar
    val _ = writeForeignLog(
      workDir,
      branch = startBranch,
      userPrompt = s"line one$esc[31m\nline two " + "x" * 80,
      flow = Some(FlowSource.Catalog(s"impl$esc[31mement.sc"))
    )
    val message =
      intercept[orca.OrcaFlowException](setupFresh(workDir): Unit).getMessage
    assert(!message.exists(_.isControl), message)
    assert(message.contains("line one[31m line two"), message)
    assert(
      message.contains("x" * 30) && !message.contains("x" * 60),
      s"a long task must be clipped: $message"
    )
    assert(message.contains("…"), message)
    assert(message.contains("impl[31mement.sc"), message)

  test(
    "setup: with several logs claiming the branch, the refusal names the newest"
  ):
    // The message tells the user to delete the log it names, so it must name
    // the one they'd actually resume — newest by mtime, as the shell's resume
    // offer picks.
    val workDir = GitRepo.seeded()
    val startBranch = new OsGitTool(workDir).currentBranch()
    val older = writeForeignLog(
      workDir,
      startBranch,
      prompt = "older task",
      userPrompt = "older task"
    )
    // The newer log is the CLI-run shape: a prompt recorded, no flow, so it
    // also covers what the message
    // can and can't say for such a header.
    val newer = writeForeignLog(
      workDir,
      startBranch,
      prompt = "newer task",
      userPrompt = "newer task",
      flow = None
    )
    // Force a distinguishable mtime order regardless of write-speed timing.
    val _ = os.mtime.set(older.path, System.currentTimeMillis() - 60000)

    val message =
      intercept[orca.OrcaFlowException](setupFresh(workDir): Unit).getMessage
    assert(
      message.contains("newer task") &&
        message.contains(newer.path.relativeTo(workDir).toString),
      s"the newest claiming log must be the one reported: $message"
    )
    assert(
      !message.contains(", flow:") && !message.contains("orca shell"),
      s"an unrecorded flow name is neither named nor implied: $message"
    )
    assert(
      !message.contains("older task") &&
        !message.contains(older.path.relativeTo(workDir).toString),
      s"only the newest log is reported: $message"
    )

  test("setup: a log naming a DIFFERENT branch leaves a fresh run alone"):
    val workDir = GitRepo.seeded()
    val startBranch = new OsGitTool(workDir).currentBranch()
    val _ = writeForeignLog(workDir, branch = "feat/elsewhere")
    assertNotEquals(setupFresh(workDir).featureBranch.value, startBranch)

  test("setup: a CORRUPT foreign log never refuses — it claims no branch"):
    val workDir = GitRepo.seeded()
    val startBranch = new OsGitTool(workDir).currentBranch()
    val foreign = writeForeignLog(workDir, branch = startBranch)
    os.write.over(foreign.path, "not json {{{")
    assertNotEquals(setupFresh(workDir).featureBranch.value, startBranch)

  test(
    "setup: a resume of the same prompt proceeds, even with a foreign log on its branch"
  ):
    // The run's OWN log naming the current branch is the legitimate resume
    // case, not a conflict — and a second log naming that branch doesn't turn
    // it into one.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val prompt = "resume-me"
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val startBranch = git.currentBranch()
    assert(git.createBranch(branchName("feat/resume-me")).isRight)
    ProgressStore
      .default(workDir, RunKey.of(prompt))
      .writeHeader(
        ProgressHeader(
          startingBranch = Some(branchName(startBranch)),
          branch = branchName("feat/resume-me"),
          branchMode = BranchMode.Created,
          userPrompt = prompt,
          flow = None,
          startingCommit = unreachableCommit
        )
      )
    val _ = writeForeignLog(workDir, branch = "feat/resume-me")

    val setup = setupForSettings(workDir, Some(StackSettings.empty), prompt)
    assertEquals(setup.featureBranch.value, "feat/resume-me")
    assertEquals(setup.startingHead, Head.OnBranch(branchName(startBranch)))

  // --- the commit the run started from (a whole-run review's diff base) ---

  test("setup: a fresh run records the commit it bound at in its header"):
    // Read at the binding point, so the run's own header commit — which moves
    // HEAD — is inside the range a whole-run review sees, not before it.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val boundAt = git.headCommit()
    val setup = setupFresh(workDir)
    assertEquals(setup.startingCommit, boundAt)
    assertEquals(
      Some(setup.store.load().get.header.startingCommit),
      boundAt
    )
    assertNotEquals(git.headCommit(), boundAt)

  test("setup: skip-branch mode records the commit it bound at too"):
    // No branch is created here, so nothing else would capture the commit.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    assert(git.createBranch(branchName("my-work")).isRight)
    val boundAt = git.headCommit()
    val prompt = "skip-branch starting commit"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val setup = FlowLifecycle.setup(
      args =
        OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
      agent = StubAgent.claude,
      git = git,
      workDir = workDir,
      branchNaming = None,
      resolution = FlowLifecycle
        .readSettings(workDir, noGlobalSettings, Some(StackSettings.empty))
        .stack,
      flowSource = None,
      store = store,
      sessionStore = scratchSessions(),
      emit = _ => ()
    )
    assertEquals(setup.startingCommit, boundAt)
    assertEquals(Some(store.load().get.header.startingCommit), boundAt)

  test("setup: a resumed run reports the commit the FIRST attempt bound at"):
    // This attempt's HEAD has moved on by everything the interrupted one
    // committed, and a whole-run review must still see those changes.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val boundAt = git.headCommit()
    val _ = setupFresh(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    os.write(workDir / "stage.txt", "done")
    assert(git.commit("stage one").isRight)
    assertEquals(setupFresh(workDir).startingCommit, boundAt)

  test("setup: a resume drops a commit this repository can no longer diff"):
    // Rebased away, or absent from a fresh clone: the hash is well-formed but
    // names nothing HEAD descends from, so diffing against it would widen the
    // review to unrelated history. Losing the base is the designed outcome.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val prompt = "resume-unreachable-commit"
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val startBranch = git.currentBranch()
    assert(
      git.createBranch(branchName("feat/resume-unreachable-commit")).isRight
    )
    ProgressStore
      .default(workDir, RunKey.of(prompt))
      .writeHeader(
        ProgressHeader(
          startingBranch = Some(branchName(startBranch)),
          branch = branchName("feat/resume-unreachable-commit"),
          branchMode = BranchMode.Created,
          startingCommit = unreachableCommit,
          userPrompt = prompt,
          flow = None
        )
      )
    val setup = setupForSettings(workDir, Some(StackSettings.empty), prompt)
    assertEquals(setup.startingCommit, None)

  // --- the branch the run bound to, announced as an event ---

  private def setupRecordingBound(
      workDir: os.Path
  ): (FlowLifecycle.FlowSetup, List[String]) =
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val setup = setupForSettings(
      workDir,
      Some(StackSettings.empty),
      "a brand new task",
      emit = e => { val _ = emitted.updateAndGet(e :: _) }
    )
    (setup, emitted.get().collect { case OrcaEvent.BranchBound(b) => b })

  test("setup: a fresh run emits the branch it bound to, once"):
    val (setup, bound) = setupRecordingBound(GitRepo.seeded())
    assertEquals(bound, List(setup.featureBranch.value))

  test("setup: a resumed run emits the branch it bound to, once"):
    val workDir = GitRepo.seeded()
    val (first, _) = setupRecordingBound(workDir)
    val (_, bound) = setupRecordingBound(workDir)
    assertEquals(bound, List(first.featureBranch.value))

  test("setup: skip-branch mode emits the current branch as the bound one"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    assert(git.createBranch(branchName("my-work")).isRight)
    val prompt = "skip-branch bound event"
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val _ = setupForSettings(
      workDir,
      Some(StackSettings.empty),
      prompt,
      emit = e => { val _ = emitted.updateAndGet(e :: _) },
      args = Some(
        OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash))
      )
    )
    assertEquals(
      emitted.get().collect { case OrcaEvent.BranchBound(b) => b },
      List("my-work")
    )

  // --- a user-chosen --branch name ---

  private def setupWithBranch(
      workDir: os.Path,
      branch: String,
      git: RuntimeGit
  ): FlowLifecycle.FlowSetup =
    val prompt = "a task with a chosen branch"
    setupForSettings(
      workDir,
      Some(StackSettings.empty),
      prompt,
      args = Some(OrcaArgs(prompt, branch = BranchName.parse(branch).toOption)),
      git = Some(git)
    )

  private def localBranches(workDir: os.Path): Set[String] =
    os.proc("git", "for-each-ref", "--format=%(refname:short)", "refs/heads")
      .call(cwd = workDir)
      .out
      .lines()
      .toSet

  test("setup: --branch wins over the branch naming strategy"):
    val workDir = GitRepo.seeded()
    val setup = setupWithBranch(workDir, "feat/x", new OsGitTool(workDir))
    assertEquals(setup.featureBranch.value, "feat/x")

  test(
    "setup: an existing --branch is refused with the --skip-branch hint, before anything is stashed"
  ):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    assert(git.createBranch(branchName("feat/x")).isRight)
    assert(git.checkout(branchName("main")).isRight)
    os.write.append(workDir / "seed.txt", " edited")
    val before = localBranches(workDir)
    val thrown = intercept[orca.OrcaFlowException]:
      setupWithBranch(workDir, "feat/x", git)
    assert(
      thrown.getMessage.contains("--skip-branch"),
      s"refusal must point at --skip-branch: ${thrown.getMessage}"
    )
    assertEquals(localBranches(workDir), before)
    assertEquals(stashList(workDir), Nil)

  test("setup: --branch differing from the resumed run's branch is refused"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val _ = setupWithBranch(workDir, "feat/x", git)
    val thrown = intercept[orca.OrcaFlowException]:
      setupWithBranch(workDir, "feat/y", git)
    assert(
      thrown.getMessage.contains("already bound to branch 'feat/x'"),
      s"refusal must name the bound branch: ${thrown.getMessage}"
    )

  test("setup: --branch equal to the resumed run's branch resumes"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val _ = setupWithBranch(workDir, "feat/x", git)
    assertEquals(
      setupWithBranch(workDir, "feat/x", git).featureBranch.value,
      "feat/x"
    )

  test("setup: --branch naming the detected default branch is refused"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir):
      override def defaultBranch(): Option[String] = Some("develop")
    val thrown = intercept[orca.OrcaFlowException]:
      setupWithBranch(workDir, "develop", git)
    assert(
      thrown.getMessage.contains("protected"),
      s"refusal must say the branch is protected: ${thrown.getMessage}"
    )
    assert(!localBranches(workDir).contains("develop"))

  test("the commit the run bound at reaches the flow body"):
    // The rest of the path FlowSetup only starts: DefaultFlowContext, and what
    // a flow body actually reads when it asks for the whole-run diff base.
    val workDir = GitRepo.seeded()
    val prompt = "starting-commit-threading"
    val boundAt = new OsGitTool(workDir).headCommit()
    var seen: Option[CommitHash] = None
    runFlowForTest(workDir, prompt):
      seen = summon[orca.FlowControl].startingCommit
    assertEquals(seen, boundAt)

  // The ADR-0019 gitignored-settings warning message.
  private val settingsIgnoredWarning =
    "stack settings at .orca/settings.properties are gitignored — remove the " +
      "'.orca/' line from .gitignore so they can be committed (scratch " +
      "self-ignores under .orca/cache/)"

  /** Runs `setup` in a seeded repo — with `.gitignore` committed first when a
    * body is given — and returns the collected Step messages. The no-override
    * arms pre-write a settings file so discovery (which the stub agent cannot
    * serve) never runs.
    */
  private def setupStepsWithGitignore(
      gitignore: Option[String],
      settingsOverride: Option[StackSettings],
      prompt: String
  ): List[String] =
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    gitignore.foreach: body =>
      given WorkspaceWrite = WorkspaceWrite.unsafe
      os.write(workDir / ".gitignore", body)
      assert(git.commit("add .gitignore").isRight)
    if settingsOverride.isEmpty then
      os.write(
        OrcaDir.settingsPath(workDir),
        "format = echo fmt\n",
        createFolders = true
      )
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val _ = FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = StubAgent.claude,
      git = git,
      workDir = workDir,
      branchNaming = None,
      resolution = FlowLifecycle
        .readSettings(workDir, noGlobalSettings, settingsOverride)
        .stack,
      flowSource = None,
      store = store,
      sessionStore = scratchSessions(),
      emit = e => { val _ = emitted.updateAndGet(e :: _) }
    )
    emitted.get().collect { case s: OrcaEvent.Step => s.message }

  test(
    "setup: warns when the settings path is gitignored (legacy .orca/ ignore)"
  ):
    val steps = setupStepsWithGitignore(
      Some(".orca/\n"),
      settingsOverride = None,
      prompt = "ignored-settings"
    )
    assert(
      steps.contains(settingsIgnoredWarning),
      s"expected the pinned gitignored-settings warning, got: $steps"
    )

  test(
    "setup: no gitignored-settings warning when the settings path is not ignored"
  ):
    val steps = setupStepsWithGitignore(
      None,
      settingsOverride = None,
      prompt = "not-ignored"
    )
    assert(
      !steps.exists(_.contains("gitignored")),
      s"no gitignored-settings warning expected, got: $steps"
    )

  test(
    "setup: no gitignored-settings warning under a programmatic override, even with the ignored path"
  ):
    // An override means the run neither reads nor writes the file — the
    // migration warning would be noise.
    val steps = setupStepsWithGitignore(
      Some(".orca/\n"),
      settingsOverride = Some(StackSettings.empty),
      prompt = "override-ignored"
    )
    assert(
      !steps.exists(_.contains("gitignored")),
      s"no gitignored-settings warning expected under an override, got: $steps"
    )

  // --- stack-settings resolution during setup (ADR 0019) --------------------

  /** Drives `setup` directly against `workDir` with a throwaway store — the
    * fixture for the stack-settings resolution tests, and (via the defaulted
    * `emit`/`tty`/`ask`) the dirty-tree prompt tests. Headless by default, so a
    * fresh dirty run stashes without asking. `args` overrides the plain
    * `OrcaArgs(prompt)`; the store stays keyed on `prompt`.
    */
  private def setupForSettings(
      workDir: os.Path,
      settingsOverride: Option[StackSettings] = None,
      prompt: String = "settings-resolution",
      emit: OrcaEvent => Unit = _ => (),
      tty: () => Boolean = () => false,
      ask: Int => DirtyTreeChoice = _ => DirtyTreeChoice.Stash,
      args: Option[OrcaArgs] = None,
      git: Option[RuntimeGit] = None
  ): FlowLifecycle.FlowSetup =
    FlowLifecycle.setup(
      args = args.getOrElse(OrcaArgs(prompt)),
      agent = StubAgent.claude,
      git = git.getOrElse(new OsGitTool(workDir)),
      workDir = workDir,
      branchNaming = None,
      resolution = FlowLifecycle
        .readSettings(workDir, noGlobalSettings, settingsOverride)
        .stack,
      flowSource = None,
      store = ProgressStore.default(workDir, RunKey.of(prompt)),
      sessionStore = scratchSessions(),
      emit = emit,
      tty = tty,
      ask = ask
    )

  test("setup: a committed settings file resolves into FlowSetup"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    os.write(
      OrcaDir.settingsPath(workDir),
      "format = cargo fmt\nlint = cargo check\ntest = cargo test\n",
      createFolders = true
    )
    assert(git.commit("add stack settings").isRight)
    val setup = setupForSettings(workDir)
    assertEquals(
      setup.stackSettings,
      StackSettings(
        format = List("cargo fmt"),
        lint = List("cargo check"),
        test = List("cargo test")
      )
    )

  test(
    "setup: a fresh run's header records userPrompt and the given flow source"
  ):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    os.write(
      OrcaDir.settingsPath(workDir),
      "format = echo fmt\n",
      createFolders = true
    )
    assert(git.commit("add stack settings").isRight)
    val prompt = "resume-header-fields"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val _ = FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = StubAgent.claude,
      git = git,
      workDir = workDir,
      branchNaming = None,
      resolution =
        FlowLifecycle.readSettings(workDir, noGlobalSettings, None).stack,
      store = store,
      sessionStore = scratchSessions(),
      flowSource = Some(FlowSource.Catalog("implement.sc")),
      emit = _ => ()
    )
    val loaded = store.load()
    assertEquals(loaded.map(_.header.userPrompt), Some(prompt))
    assertEquals(
      loaded.map(_.header.flow),
      Some(Some(FlowSource.Catalog("implement.sc")))
    )

  test(
    "setup: a fresh run's header has no flow when not given (a run outside the shell)"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "no-flow-name"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val _ = setupForSettings(
      workDir,
      settingsOverride = Some(StackSettings.empty),
      prompt = prompt
    )
    val loaded = store.load()
    assertEquals(loaded.map(_.header.flow), Some(None))

  test(
    "setup: an UNTRACKED settings file in a dirty tree is read before the stash sweeps it"
  ):
    // The read happens pre-`ensureClean`: the stash sweeps the untracked file
    // away (asserted below), so the values held in FlowSetup can only come
    // from the pre-stash read.
    val workDir = GitRepo.seeded()
    os.write(
      OrcaDir.settingsPath(workDir),
      "lint = npm run lint\n",
      createFolders = true
    )
    val setup = setupForSettings(workDir)
    assert(
      !os.exists(OrcaDir.settingsPath(workDir)),
      "stash must have swept the untracked settings file"
    )
    assertEquals(
      setup.stackSettings,
      StackSettings(lint = List("npm run lint"))
    )

  test(
    "setup: a malformed settings file aborts BEFORE ensureClean — no stash, branch unchanged"
  ):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    os.write(
      OrcaDir.settingsPath(workDir),
      "not-a-key = cargo fmt\n",
      createFolders = true
    )
    val startBranch = git.currentBranch()
    val thrown = intercept[orca.OrcaFlowException]:
      setupForSettings(workDir)
    assert(
      thrown.getMessage.contains("invalid settings") &&
        thrown.getMessage.contains(OrcaDir.settingsPath(workDir).toString) &&
        thrown.getMessage.contains("not-a-key"),
      s"abort message must name the path and the parser's error: ${thrown.getMessage}"
    )
    val stashes =
      os.proc("git", "stash", "list").call(cwd = workDir).out.text().trim
    assertEquals(stashes, "", "the abort must precede the ensureClean stash")
    assertEquals(
      git.currentBranch(),
      startBranch,
      "the abort must precede any branch mutation"
    )

  test("readSettings: a stack key with an empty value needs discovery"):
    val workDir = GitRepo.seeded()
    val content = "format =\n"
    os.write(OrcaDir.settingsPath(workDir), content, createFolders = true)
    assertEquals(
      FlowLifecycle.readSettings(workDir, noGlobalSettings, None).stack,
      FlowLifecycle.SettingsResolution.NeedsDiscovery(Some(content))
    )

  test("setup: an explicit override wins over a present file, file untouched"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val fileContent = "format = cargo fmt\n"
    os.write(OrcaDir.settingsPath(workDir), fileContent, createFolders = true)
    assert(git.commit("add stack settings").isRight)
    val override_ = StackSettings(format = List("scalafmt"))
    val setup = setupForSettings(workDir, settingsOverride = Some(override_))
    assertEquals(setup.stackSettings, override_)
    assertEquals(os.read(OrcaDir.settingsPath(workDir)), fileContent)

  /** Drives `setup` with no settings file and no override, so discovery runs
    * against `agent`; returns the setup outcome and the collected Step
    * messages. The store is resolved exactly as production does, so a pre-built
    * progress log in `workDir` makes this the resume arm.
    */
  private def setupDiscovering(
      workDir: os.Path,
      agent: Agent[?],
      prompt: String,
      target: RunTarget = RunTarget.NewBranch(Uncommitted.Stash)
  ): (FlowLifecycle.FlowSetup, List[String]) =
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val setup = FlowLifecycle.setup(
      args = OrcaArgs(prompt, target = target),
      agent = agent,
      git = new OsGitTool(workDir),
      workDir = workDir,
      branchNaming = None,
      resolution = FlowLifecycle
        .readSettings(workDir, noGlobalSettings, None)
        .stack,
      flowSource = None,
      store = ProgressStore.default(workDir, RunKey.of(prompt)),
      sessionStore = scratchSessions(),
      emit = e => { val _ = emitted.updateAndGet(e :: _) }
    )
    (
      setup,
      emitted.get().reverse.collect { case s: OrcaEvent.Step => s.message }
    )

  /** The paths a single commit touched (`git show --name-only`), sorted, so an
    * assertion can pin a commit to EXACTLY its files.
    */
  private def commitFiles(workDir: os.Path, rev: String): List[String] =
    os.proc("git", "show", "--name-only", "--pretty=format:", rev)
      .call(cwd = workDir)
      .out
      .text()
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
      .sorted

  /** A single commit's subject line (`git log -1 --pretty=format:%s`). */
  private def commitMessage(workDir: os.Path, rev: String): String =
    os.proc("git", "log", "-1", "--pretty=format:%s", rev)
      .call(cwd = workDir)
      .out
      .text()
      .trim

  test(
    "setup with --keep-changes and discovery: the kept snapshot is based on the settings commit"
  ):
    // Teardown restores only while HEAD is at the snapshot's base, so a base
    // behind setup's last commit would never be restored.
    val workDir = GitRepo.seeded()
    os.write.over(workDir / "seed.txt", "modified in place")
    val (setup, _) = setupDiscovering(
      workDir,
      CannedDiscoveryAgent(
        StackDiscoveryResult(
          format = DiscoveredGate(commands =
            List(DiscoveredCommand("echo fmt", "seed.txt"))
          ),
          lint = DiscoveredGate(),
          test = DiscoveredGate()
        )
      ),
      "discover-keep",
      target = keepChanges
    )
    setup.startingTree match
      case StartingTree.Kept(Some(snapshot)) =>
        assertEquals(Some(snapshot.base), new OsGitTool(workDir).headCommit())
      case other => fail(s"expected a kept snapshot, got $other")

  test(
    "setup: fresh arm, no file, no override — discovery gives the settings file its own commit, after the header commit"
  ):
    val workDir = GitRepo.seeded()
    val canned = StackDiscoveryResult(
      format = DiscoveredGate(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt", Some("seeded fixture")))
      ),
      lint = DiscoveredGate(unsetReason = Some("no lint config found")),
      test = DiscoveredGate()
    )
    val (setup, steps) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), "discover-fresh")
    assertEquals(setup.stackSettings, StackSettings(format = List("echo fmt")))
    // The written file is the byte-exact render of the checked entries.
    assertEquals(
      os.read(OrcaDir.settingsPath(workDir)),
      """# orca settings — edit freely, commit with the project.
        |# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
        |# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
        |# seed.txt; seeded fixture
        |format = echo fmt
        |# no lint config found
        |lint = off
        |# no evidence found
        |test = off
        |""".stripMargin
    )
    // The dedicated settings commit sits immediately after the header commit
    // (HEAD~1), carries EXACTLY the settings file, and bears the pinned message.
    assertEquals(
      commitFiles(workDir, "HEAD"),
      List(".orca/settings.properties")
    )
    assertEquals(
      commitMessage(workDir, "HEAD"),
      "orca: stack settings (discovered)"
    )
    // The header commit carries only the progress log, not the settings file.
    assertEquals(commitMessage(workDir, "HEAD~1"), "orca: progress log")
    assert(
      !commitFiles(workDir, "HEAD~1").contains(".orca/settings.properties"),
      s"the header commit must NOT include the settings file, got: ${commitFiles(workDir, "HEAD~1")}"
    )
    assert(
      steps.contains(
        "no .orca/settings.properties — discovering how to format, lint & test this project"
      ),
      s"expected the running-discovery Step, got: $steps"
    )
    assert(
      steps.contains(
        "written to .orca/settings.properties — review and edit as needed."
      ),
      s"expected the written Step, got: $steps"
    )

  test(
    "discovery: an unresolvable command is demoted in the file, with a gate-disabled warning"
  ):
    val workDir = GitRepo.seeded()
    val canned = StackDiscoveryResult(
      format = DiscoveredGate(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredGate(commands =
        List(DiscoveredCommand("definitely-not-a-cmd-xyz check", "seed.txt"))
      ),
      test = DiscoveredGate(commands =
        List(DiscoveredCommand("echo test", "seed.txt"))
      )
    )
    val (setup, steps) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), "discover-demote")
    val content = os.read(OrcaDir.settingsPath(workDir))
    assert(
      content.contains(
        "# skipped: lint = definitely-not-a-cmd-xyz check " +
          "(definitely-not-a-cmd-xyz: not found on PATH)\nlint = off"
      ),
      s"the demoted command must be a comment, followed by a live `off` " +
        s"line: $content"
    )
    // The written file parses to only the surviving commands, matching the run's.
    assertEquals(
      orca.settings.SettingsFile
        .parse(content, orca.settings.SettingsScope.Project)
        .map(_.stack),
      Right(
        Some(
          StackSettings(format = List("echo fmt"), test = List("echo test"))
        )
      )
    )
    assertEquals(setup.stackSettings.lint, Nil)
    assert(
      steps.contains(
        "warning: stack settings: no lint command — gate disabled"
      ),
      s"expected the gate-disabled warning for lint, got: $steps"
    )

  test(
    "discovery: resume arm (log present, file deleted) rediscovers and gives the file its own commit"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "discover-resume"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    // The delete-to-rediscover fixture: feature branch, committed header — and
    // NO settings file when the run resumes.
    val _ = git.createBranch(branchName("feat/discover-resume"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/discover-resume"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val headBefore =
      os.proc("git", "rev-parse", "HEAD").call(cwd = workDir).out.text().trim
    val canned = StackDiscoveryResult(
      format = DiscoveredGate(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredGate(),
      test = DiscoveredGate()
    )
    val (setup, _) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), prompt)
    assertEquals(setup.stackSettings, StackSettings(format = List("echo fmt")))
    // The resume arm gives the rediscovered file its own dedicated commit (the
    // branch already existed): HEAD advances by exactly that commit, carrying
    // exactly the settings file under the pinned message.
    assertEquals(
      os.proc("git", "rev-parse", "HEAD~1").call(cwd = workDir).out.text().trim,
      headBefore,
      "the dedicated settings commit is the only new commit on the resume arm"
    )
    assertEquals(
      commitMessage(workDir, "HEAD"),
      "orca: stack settings (discovered)"
    )
    assertEquals(
      commitFiles(workDir, "HEAD"),
      List(".orca/settings.properties")
    )

  test(
    "discovery: legacy-ignored repo — file written, no dedicated commit, stays untracked, ignored-warning fires"
  ):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    locally:
      given WorkspaceWrite = WorkspaceWrite.unsafe
      os.write(workDir / ".gitignore", ".orca/\n")
      assert(git.commit("add .gitignore").isRight)
    val canned = StackDiscoveryResult(
      format = DiscoveredGate(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredGate(),
      test = DiscoveredGate()
    )
    val (_, steps) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), "discover-legacy")
    assert(
      os.exists(OrcaDir.settingsPath(workDir)),
      "the file must be written even in a legacy-ignored repo"
    )
    // The dedicated commit is SKIPPED when the path is ignored: neither the
    // header commit nor any settings commit carries the file, and it stays
    // ignored on disk for the user to commit after fixing the ignore.
    assert(
      !commitFiles(workDir, "HEAD").contains(".orca/settings.properties"),
      s"an ignored settings file must not ride the header commit: ${commitFiles(workDir, "HEAD")}"
    )
    assertNotEquals(
      commitMessage(workDir, "HEAD"),
      "orca: stack settings (discovered)",
      "no dedicated settings commit may exist in a legacy-ignored repo"
    )
    val tracked = os
      .proc("git", "ls-files", "--", ".orca/settings.properties")
      .call(cwd = workDir)
      .out
      .text()
      .trim
    assertEquals(
      tracked,
      "",
      "an ignored settings file stays untracked after discovery"
    )
    assert(
      steps.contains(settingsIgnoredWarning),
      s"the ADR-0019 ignored-settings warning must fire, got: $steps"
    )

  test(
    "discovery: a failing agent aborts setup as a surfaced failure — no file written, no stage ran"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "discover-failure"
    var stageRan = false
    val throwing =
      CannedDiscoveryAgent(throw new RuntimeException("discovery boom"))
    val thrown = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(prompt),
            wiring = FlowWiring(claude = Some(_ => throwing)),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = Nil,
            branchNaming = None
          )
        ):
          val _ = stage("never-runs"):
            stageRan = true
            "x"
    assertEquals(thrown.cause.getMessage, "discovery boom")
    // NEVER degraded to writing an empty/all-commented file (ADR 0019): the
    // frozen-file semantics would make a transient outage permanent.
    assert(
      !os.exists(OrcaDir.settingsPath(workDir)),
      "a discovery failure must not write a settings file"
    )
    assert(!stageRan, "setup aborts before any stage can run")

  test(
    "discovery: an all-unset result writes an all-`off` file, warns per gate, and yields empty settings"
  ):
    val workDir = GitRepo.seeded()
    val canned = StackDiscoveryResult(
      format = DiscoveredGate(unsetReason = Some("no formatter config found")),
      lint = DiscoveredGate(),
      test = DiscoveredGate(unsetReason = Some("no test directory found"))
    )
    val (setup, steps) = setupDiscovering(
      workDir,
      CannedDiscoveryAgent(canned),
      "discover-all-unset"
    )
    assertEquals(setup.stackSettings, StackSettings.empty)
    val content = os.read(OrcaDir.settingsPath(workDir))
    assertEquals(
      content,
      """# orca settings — edit freely, commit with the project.
        |# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
        |# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
        |# no formatter config found
        |format = off
        |# no evidence found
        |lint = off
        |# no test directory found
        |test = off
        |""".stripMargin
    )
    // Discovery's own written output must not re-trigger discovery: every
    // gate is a live `off` line, not a comment.
    assertEquals(
      orca.settings.SettingsFile
        .parse(content, orca.settings.SettingsScope.Project)
        .map(_.stack),
      Right(Some(StackSettings.empty))
    )
    val warnings = steps.filter(_.contains("gate disabled"))
    assertEquals(
      warnings,
      List(
        "warning: stack settings: no format command — gate disabled",
        "warning: stack settings: no lint command — gate disabled",
        "warning: stack settings: no test command — gate disabled"
      )
    )

  /** Drive `runFlow` directly (exit-free) with a null-sink interaction so no
    * TTY is needed and a body failure surfaces as a thrown exception rather
    * than a `System.exit`.
    */
  /** A session store over a scratch directory, for fixtures that build a
    * `FlowSetup` or call `setup` directly: neither reads the records, and
    * `teardownSuccess` only discards them.
    */
  private def scratchSessions(): SessionStore =
    SessionStore.default(TempDirs.dir(), RunKey.of("fixture"))

  private val keepChanges: RunTarget = RunTarget.NewBranch(Uncommitted.Keep)

  private def runFlowForTest(
      workDir: os.Path,
      prompt: String,
      extraListeners: List[OrcaListener] = Nil,
      claude: ClaudeAgent = StubAgent.claude,
      gh: Option[GitHubTool] = None,
      git: Option[RuntimeGit] = None,
      target: RunTarget = RunTarget.NewBranch(Uncommitted.Stash)
  )(body: (orca.FlowContext, orca.FlowControl) ?=> Unit): Unit =
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        FlowHarness.request(
          args = OrcaArgs(prompt, target = target),
          stackSettings = Some(StackSettings.empty),
          wiring = FlowWiring(claude = Some(_ => claude), gh = gh, git = git),
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = extraListeners,
          branchNaming = None
        )
      )(body)

  test(
    "teardownSuccess is best-effort: a non-missing-file removal error does not fail an otherwise-successful run"
  ):
    // teardownSuccess swallows log-removal/commit/handoff errors as cosmetic.
    // Replace the progress-log file with a non-empty directory (after the last
    // stage committed it as a plain file) so `os.remove` throws
    // DirectoryNotEmptyException — a real, not-NoSuchFile IO error. The run must
    // still complete: nothing should escape teardown and turn success into exit 1.
    val workDir = GitRepo.seeded()
    val prompt = "bestEffort-teardown"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    runFlowForTest(workDir, prompt):
      val _ = stage("stage-one"):
        os.write(workDir / "one.txt", "content")
        "one-done"
      val _ = os.remove(store.path)
      os.makeDir.all(store.path)
      os.write(store.path / "blocker.txt", "x")
    // Reaching here (no thrown exception) is the assertion: teardownSuccess
    // must not propagate the removal failure.
    assert(
      os.isDir(store.path),
      "the corrupted path should still be a (now-orphaned) directory: " +
        "teardownSuccess's removal attempt must have failed and been swallowed"
    )

  test(
    "runFlow closes the agents even when the body throws"
  ):
    // Wire a recording opencode agent and assert its close() ran after a body
    // that throws.
    val workDir = GitRepo.seeded()
    val prompt = "close-on-body-throw"
    val opencodeBackend = ScriptedBackend.unused(BackendTag.Opencode)
    val recorder: OpencodeAgent = TestAgent(opencodeBackend)
    val thrown = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(prompt),
            stackSettings = Some(StackSettings.empty),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = Nil,
            branchNaming = None,
            wiring = FlowWiring(
              claude = Some(_ => StubAgent.claude),
              opencode = Some(_ => recorder)
            )
          )
        ):
          throw new RuntimeException("boom in body")
    assertEquals(thrown.cause.getMessage, "boom in body")
    assert(
      opencodeBackend.isClosed,
      "the opencode agent must be closed on the failure path too"
    )

  test(
    "R5: success teardown auto-deletes feature branch when only orca commits exist"
  ):
    // A flow whose body does nothing besides getting staged (only the orca
    // progress header + removal commits are on the feature branch). On success,
    // the branch should be gone.
    val workDir = GitRepo.seeded()
    val prompt = "throwaway-flow"
    val git = new OsGitTool(workDir)
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        // body does nothing — no code changes
        summon[orca.FlowContext].emit(OrcaEvent.Step("no-op"))
    assertEquals(git.currentBranch(), "main")
    // The feature branch must be gone (auto-deleted as throwaway): only main left.
    val branches = os
      .proc("git", "branch", "--format=%(refname:short)")
      .call(cwd = workDir)
      .out
      .text()
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet
    assertEquals(branches, Set("main"), s"expected only main, got: $branches")

  test(
    "success teardown (default): stays on the feature branch when code landed"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "code-flow"
    val git = new OsGitTool(workDir)
    var featureBranchName = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        // Record the feature branch name before it commits (during stage body).
        featureBranchName = summon[orca.FlowContext].git.currentBranch()
        val _ = stage("write code"):
          os.write(workDir / "code.txt", "real code")
          "done"
    // Default behaviour: stay on the feature branch (the user ends on the work).
    assertEquals(git.currentBranch(), featureBranchName)
    assert(featureBranchName.nonEmpty, "must have captured feature branch name")
    val branches = os
      .proc("git", "branch", "--format=%(refname:short)")
      .call(cwd = workDir)
      .out
      .text()
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet
    assert(
      branches.contains(featureBranchName),
      s"feature branch '$featureBranchName' must be kept; branches: $branches"
    )

  // ── the PR-driven branch handoff ─────────────────────────────────────────

  private val handoffPr =
    prHandle("https://github.com/acme/widgets/pull/7")

  /** [[handoffPr]] as the lifecycle reads it back out of the progress log. */
  private val handoffPublished =
    PublishedState.Published(PublishedWork(handoffPr.url))

  /** A `gh` on GitHub that opens [[handoffPr]]; the lifecycle touches nothing
    * else on it.
    */
  private class StubGh extends StubGitHubTool:
    override def availability(): GitHubAvailability =
      GitHubAvailability.Available(host = "github.com", owner = "a", repo = "w")
    override def createPr(title: String, body: String)(using WorkspaceWrite) =
      Right(handoffPr)

  /** A claude whose structured call answers with a fixed [[PrSummary]] — what
    * the summarise stage of [[openPrIfGitHub]] needs. Free-text turns fail.
    */
  private def summarisingClaude: ClaudeAgent =
    TestAgent(
      ScriptedBackend.replying(BackendTag.ClaudeCode): turn =>
        if turn.outputSchema.isEmpty then
          throw new UnsupportedOperationException("free-text turn")
        ScriptedBackend.json(PrSummary("Generated title", "Generated body"))
      ,
      "summariser"
    )

  /** Where one `openPrIfGitHub` run left the checkout. */
  private case class HandoffRun(head: Head, featureBranch: String)

  /** A run that writes a file and then calls [[openPrIfGitHub]] on GitHub. */
  private def handoffRun(workDir: os.Path = GitRepo.seeded()): HandoffRun =
    val prompt = "pr-handoff"
    val git = new OsGitTool(workDir)
    var featureBranch = ""
    runFlowForTest(
      workDir,
      prompt,
      claude = summarisingClaude,
      gh = Some(new StubGh),
      git = Some(new PushlessGit(new OsGitTool(workDir)))
    ):
      featureBranch = summon[FlowContext].git.currentBranch()
      val _ = stage("write code"):
        os.write(workDir / "code.txt", "real code")
        "done"
      val _ =
        orca.pr.openPrIfGitHub(
          summarisingAgent = summon[FlowContext].claude,
          openFindings = orca.review.OpenFindings.empty
        )
    HandoffRun(git.head(), featureBranch)

  test("a new-branch run that opened a PR is handed back its start branch"):
    assertEquals(handoffRun().head, Head.OnBranch(branchName("main")))

  test("a run started detached that opened a PR is handed back that commit"):
    // A CI checkout: the run must still see its code and open the PR.
    val workDir = GitRepo.seeded()
    val _ = os.proc("git", "checkout", "--detach").call(cwd = workDir)
    val start = GitRepo.headCommit(workDir)
    val r = handoffRun(workDir)
    assertEquals(r.head, Head.Detached(start))
    assert(branchNames(workDir).contains(r.featureBranch), branchNames(workDir))

  test("a run that recorded a PR keeps the empty branch it was opened from"):
    // Pins the read/teardown pair: `run` reads `published` out of the log
    // BEFORE teardown deletes the log, and the branch carries nothing but
    // orca's own commits — so a read taken after the call would see nothing
    // published and delete it.
    val workDir = GitRepo.seeded()
    val prompt = "recorded-pr-throwaway"
    var featureBranch = ""
    runFlowForTest(workDir, prompt):
      featureBranch = summon[FlowContext].git.currentBranch()
      val _ = stage("open PR"):
        orca.pr.recordOpenedPr(handoffPr)
        "done"
    assert(
      branchNames(workDir).contains(featureBranch),
      s"'$featureBranch' must survive teardown: ${branchNames(workDir)}"
    )

  test("a run inside a worktree that opened a PR stays on the work"):
    // The worktree reports RunTarget.NewBranch — a resume relaunched without
    // --worktree looks exactly like this — so `flowSetup.worktree` is the only
    // thing keeping teardown from checking the worktree's own branch out.
    val repo = GitRepo.seeded()
    val worktree = WorktreeRun.resolve(repo, RunKey.of("pr-handoff")) match
      case Right(dir) => dir
      case Left(msg)  => fail(s"could not create the worktree: $msg")
    val r = handoffRun(worktree)
    assertEquals(r.head, Head.OnBranch(branchName(r.featureBranch)))

  test("R5: failure teardown keeps feature branch regardless of code changes"):

    // A flow that crashes must NOT delete the branch — it needs to stay for resume.
    val workDir = GitRepo.seeded()
    val prompt = "failure-keeps-branch"
    val git = new OsGitTool(workDir)
    var featureBranchName = ""
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        // Capture the feature branch name before the crash.
        featureBranchName = orca.git.currentBranch()
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom")
    assertNotEquals(git.currentBranch(), "main")
    assert(featureBranchName.nonEmpty, "must have captured feature branch name")
    val branches = os
      .proc("git", "branch", "--format=%(refname:short)")
      .call(cwd = workDir)
      .out
      .text()
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet
    assert(
      branches.contains(featureBranchName),
      s"feature branch '$featureBranchName' must survive failure: $branches"
    )

  test(
    "default branchNaming (None) resolves via shortenPrompt: branch name equals slug(prompt)"
  ):
    // With `branchNaming = None`, setup uses `BranchNamingStrategy.shortenPrompt`.
    // `StubAgent.claude`'s `autonomous` throws, so `shortenPrompt` catches and
    // falls back to `slug(userPrompt)`.
    val workDir = GitRepo.seeded()
    val prompt = "default-naming"
    val expectedBranch = BranchNamingStrategy.slug(prompt)
    var observedBranch = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      // branchNaming defaults to None — do not pass it.
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        observedBranch = summon[orca.FlowContext].git.currentBranch()
    assertEquals(
      observedBranch,
      expectedBranch,
      s"default branchNaming must use shortenPrompt (slug fallback); got '$observedBranch'"
    )

  test(
    "fresh run refuses to bind to a protected branch name"
  ):
    // A `branchNaming` strategy that resolves to "main" must NOT bind the flow
    // to the repo's default branch: it falls back to a feature branch and emits
    // a protecting Step.
    val workDir = GitRepo.seeded()
    val prompt = "fresh-protected"
    val git = new OsGitTool(workDir)
    val listener = new RecordingListener
    var featureBranchName = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener),
        branchNaming = Some(BranchNamingStrategy.fromText("main"))
      ):
        // Record the feature branch before any teardown runs.
        featureBranchName = summon[orca.FlowContext].git.currentBranch()
        val _ = stage("write code"):
          os.write(workDir / "code.txt", "real code")
          "done"
    assertNotEquals(
      featureBranchName,
      "main",
      "a fresh run must never bind to the protected default branch"
    )
    assertEquals(
      git.currentBranch(),
      featureBranchName,
      "run should stay on the (fallback) feature branch since code landed"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(s =>
        s.message.contains("protected") && s.message.contains("main")
      ),
      s"expected a Step warning naming the protected branch and the fallback, got: $steps"
    )

  test(
    "fresh run does not silently adopt a pre-existing branch with the same resolved name"
  ):
    // A fresh run must not silently adopt a pre-existing branch with the resolved
    // name, binding the run to its unrelated history. Pre-create "taken-name"
    // with a commit of its own, then run a fresh flow that resolves to that same
    // name: it must fall back and leave the pre-existing branch untouched.
    val workDir = GitRepo.seeded()
    val prompt = "adoption-hazard"
    val git = new OsGitTool(workDir)
    val listener = new RecordingListener
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("taken-name"))
    os.write(workDir / "unrelated.txt", "pre-existing work")
    val _ = git.commit("unrelated pre-existing commit")
    val preExistingHead = os
      .proc("git", "rev-parse", "taken-name")
      .call(cwd = workDir)
      .out
      .text()
      .trim
    val _ = git.checkout(branchName("main"))
    var featureBranchName = ""
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener),
        branchNaming = Some(BranchNamingStrategy.fromText("taken-name"))
      ):
        // Record the feature branch before any teardown runs.
        featureBranchName = summon[orca.FlowContext].git.currentBranch()
        val _ = stage("write code"):
          os.write(workDir / "code.txt", "real code")
          "done"
    assertNotEquals(
      featureBranchName,
      "taken-name",
      "a fresh run must never silently adopt a pre-existing branch's history"
    )
    // The pre-existing branch itself must be untouched: same head, and the
    // run's own commits never landed on it.
    val afterHead = os
      .proc("git", "rev-parse", "taken-name")
      .call(cwd = workDir)
      .out
      .text()
      .trim
    assertEquals(
      afterHead,
      preExistingHead,
      "the pre-existing 'taken-name' branch must be untouched by the run"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(s =>
        s.message.contains("taken-name") && s.message.contains("already exists")
      ),
      s"expected a Step warning naming the pre-existing branch and the fallback, got: $steps"
    )

  test(
    "fresh run aborts loudly when the deterministic fallback branch itself already exists"
  ):
    // If the resolved name already went to the deterministic `flow-<hash>`
    // fallback (here because a protected-name collision fires first), a git
    // "already exists" collision on THAT fallback must abort rather than hash
    // again: a deterministic name colliding twice for the same prompt means a
    // previous run's branch is still there, and the user must decide. Pre-create
    // the fallback name, then force the protected-name fallback onto it by
    // resolving to "main".
    val workDir = GitRepo.seeded()
    val prompt = "fallback-collision-hazard"
    val git = new OsGitTool(workDir)
    val expectedFallback = BranchNamingStrategy.flowFallbackName(prompt)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName(expectedFallback))
    val _ = git.checkout(branchName("main"))
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(prompt),
            stackSettings = Some(StackSettings.empty),
            wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = List(listener),
            branchNaming = Some(BranchNamingStrategy.fromText("main"))
          )
        ):
          val _ = stage("never-runs")("x")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assert(
      thrown.cause.getMessage.contains(expectedFallback),
      s"abort message must name the colliding branch: ${thrown.cause.getMessage}"
    )
    // The repo must not have been left on the colliding branch.
    assertEquals(git.currentBranch(), "main")

  // --- skip-branch mode (ADR 0018 amendment) ---

  private def branchNames(workDir: os.Path): Set[String] =
    os.proc("git", "branch", "--format=%(refname:short)")
      .call(cwd = workDir)
      .out
      .text()
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSet

  test(
    "skip-branch mode on a non-default branch: no new branch is created, header commit lands there"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-reuse"
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    val branchesBefore = branchNames(workDir)
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("write code"):
          os.write(workDir / "code.txt", "real code")
          "done"
    assertEquals(
      git.currentBranch(),
      "my-work",
      "the run must stay bound to the reused current branch"
    )
    assertEquals(
      branchNames(workDir),
      branchesBefore,
      "skip-branch mode must not create any new branch"
    )
    val log = os
      .proc("git", "log", "-5", "--pretty=format:%s")
      .call(cwd = workDir)
      .out
      .text()
    assert(
      log.contains("progress log"),
      s"the header commit must still land, on the reused branch: $log"
    )

  test(
    "skip-branch mode on a dirty tree: failure teardown keeps the untracked files it started with"
  ):
    // A fresh skip-branch run tolerates a dirty tree instead of stashing it, so
    // those untracked files pre-date the run — its hand-off context, not its
    // output. Teardown's untracked removal must therefore be off here.
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-dirty-teardown"
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    os.write(workDir / "handoff.md", "the plan")
    val _ = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(
              prompt,
              target = RunTarget.CurrentBranch(Uncommitted.Stash)
            ),
            stackSettings = Some(StackSettings.empty),
            wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = Nil,
            branchNaming = None
          )
        ):
          val _ = stage[String]("crash"):
            throw new RuntimeException("boom body")
    assert(
      os.exists(workDir / "handoff.md"),
      "a file that pre-dated the run must survive failure teardown"
    )

  test(
    "failure teardown removes the untracked file a failed stage created"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "teardown-removes-untracked"
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        val _ = stage[String]("crash"):
          os.write(workDir / "half-written.txt", "partial")
          throw new RuntimeException("boom body")
    assert(
      !os.exists(workDir / "half-written.txt"),
      "the failed stage's new file must not survive teardown"
    )

  test("skip-branch mode refuses to bind to a protected branch"):
    val workDir = GitRepo.seeded() // starts on "main"
    val prompt = "skip-branch-protected"
    val git = new OsGitTool(workDir)
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(
              prompt,
              target = RunTarget.CurrentBranch(Uncommitted.Stash)
            ),
            stackSettings = Some(StackSettings.empty),
            wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = List(listener),
            branchNaming = None
          )
        ):
          val _ = stage("never-runs")("x")
    assert(
      thrown.cause.getMessage.contains("protected") &&
        thrown.cause.getMessage.contains("main"),
      s"abort message must name the protected current branch: ${thrown.cause.getMessage}"
    )
    assertEquals(git.currentBranch(), "main")

  test("skip-branch mode refuses on detached HEAD"):
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-detached"
    val _ = os.proc("git", "checkout", "--detach").call(cwd = workDir)
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(
              prompt,
              target = RunTarget.CurrentBranch(Uncommitted.Stash)
            ),
            stackSettings = Some(StackSettings.empty),
            wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = List(listener),
            branchNaming = None
          )
        ):
          val _ = stage("never-runs")("x")
    assert(
      thrown.cause.getMessage.contains("detached HEAD"),
      s"abort message must name detached HEAD: ${thrown.cause.getMessage}"
    )

  test(
    "a run started on a detached HEAD resumes, then ends detached at its start"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "detached-resume"
    val _ = os.proc("git", "checkout", "--detach").call(cwd = workDir)
    val start = GitRepo.headCommit(workDir)
    val stageOneRuns = new AtomicInteger(0)
    var featureBranch = ""
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt):
        featureBranch = summon[FlowContext].git.currentBranch()
        val _ = stage("stage-one"):
          stageOneRuns.incrementAndGet()
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom")
    runFlowForTest(workDir, prompt):
      val _ = stage("stage-one"):
        stageOneRuns.incrementAndGet()
        "one-done"
      val _ = stage("stage-two")("two-done")
    assertEquals(stageOneRuns.get(), 1, "stage one must replay, not re-run")
    // Nothing but orca's log landed on the branch, so it is deleted.
    assertEquals(new OsGitTool(workDir).head(), Head.Detached(start))
    assert(!branchNames(workDir).contains(featureBranch), branchNames(workDir))

  /** `git stash list`, one entry per line, for asserting no/some stash was
    * created.
    */
  private def stashList(workDir: os.Path): List[String] =
    os.proc("git", "stash", "list")
      .call(cwd = workDir)
      .out
      .text()
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  test(
    "skip-branch mode, FRESH run: a MODIFIED tracked file stays dirty through the body and is swept into the stage commit"
  ):
    val workDir = GitRepo.seeded() // commits "seed.txt"
    val prompt = "skip-branch-dirty-modified"
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    os.write.over(workDir / "seed.txt", "modified in place")
    val listener = new RecordingListener
    var modifiedInBody = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener)
      ):
        val _ = stage("write code"):
          modifiedInBody = os.read(workDir / "seed.txt") == "modified in place"
          "done"
    assert(
      modifiedInBody,
      "the modified file must still be dirty when the stage body runs — not stashed away"
    )
    assertEquals(
      stashList(workDir),
      Nil,
      "a fresh skip-branch run must never stash"
    )
    assert(
      git.dirtyPaths().isEmpty,
      "the modification must have been swept into a commit by run's end"
    )
    assertEquals(os.read(workDir / "seed.txt"), "modified in place")
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("leaving 1 uncommitted/untracked file")),
      s"expected the leftover-file notice: $steps"
    )
    // Pin WHICH commit the leftover lands in: the header commit (HEAD~2,
    // before the stage commit and the final log-removal commit) must carry
    // ONLY the progress log; `seed.txt`'s modification must reach the branch
    // via the first stage's own `add -A` commit (HEAD~1), not the header's.
    val progressRelPath =
      s".orca/runs/${RunKey.of(prompt).value}.progress.json"
    assertEquals(commitMessage(workDir, "HEAD~2"), "orca: progress log")
    assertEquals(
      commitFiles(workDir, "HEAD~2"),
      List(progressRelPath),
      "the header commit must carry ONLY the progress log"
    )
    assertEquals(commitMessage(workDir, "HEAD~1"), "stage: write code")
    assertEquals(
      commitFiles(workDir, "HEAD~1"),
      List(progressRelPath, "seed.txt"),
      "the first stage commit must carry the leftover modification"
    )

  test(
    "skip-branch mode, FRESH run: an UNTRACKED file stays in the tree through the body and is swept into the stage commit"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-dirty-untracked"
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    os.write(workDir / "docs" / "plan.md", "plan notes", createFolders = true)
    var presentInBody = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("write code"):
          presentInBody = os.exists(workDir / "docs" / "plan.md")
          "done"
    assert(
      presentInBody,
      "the untracked file must still be present when the stage body runs"
    )
    assertEquals(
      stashList(workDir),
      Nil,
      "a fresh skip-branch run must never stash"
    )
    val tracked =
      os.proc("git", "ls-files")
        .call(cwd = workDir)
        .out
        .text()
        .linesIterator
        .toSet
    assert(
      tracked.contains("docs/plan.md"),
      "the leftover file must have been swept into a commit by run's end"
    )
    // Pin WHICH commit: the header (HEAD~2) carries ONLY the progress log;
    // `docs/plan.md` reaches the branch via the first stage's commit (HEAD~1).
    val progressRelPath =
      s".orca/runs/${RunKey.of(prompt).value}.progress.json"
    assertEquals(commitMessage(workDir, "HEAD~2"), "orca: progress log")
    assertEquals(
      commitFiles(workDir, "HEAD~2"),
      List(progressRelPath),
      "the header commit must carry ONLY the progress log"
    )
    assertEquals(commitMessage(workDir, "HEAD~1"), "stage: write code")
    assertEquals(
      commitFiles(workDir, "HEAD~1"),
      List(progressRelPath, "docs/plan.md"),
      "the first stage commit must carry the leftover untracked file"
    )

  test(
    "skip-branch mode, FRESH run: a modified file, an untracked file, and .orca-adjacent noise together — no crash, nothing stashed"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-dirty-mixed"
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    os.write.over(workDir / "seed.txt", "modified in place")
    os.write(workDir / "docs" / "plan.md", "plan notes", createFolders = true)
    // Noise living right next to orca's own directory — must not confuse the
    // dirty-file count or the discovery/log machinery.
    os.write(
      workDir / ".orca" / "cache" / "leftover.tmp",
      "noise",
      createFolders = true
    )
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("write code")("done")
    assertEquals(
      stashList(workDir),
      Nil,
      "no stash entries may be created by a fresh skip-branch run"
    )

  test(
    "skip-branch mode, FRESH run whose own log is unparseable: the dirty tree is stashed, not tolerated"
  ):
    // A corrupt own log may be a broken in-progress edit of a good committed
    // one, so it takes the stash arm in every mode: skip-branch mode's
    // tolerance covers an ABSENT log only.
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-corrupt-log"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    os.write(store.path, "not json {{{", createFolders = true)
    os.write(workDir / "handoff.md", "the plan")
    os.write.over(workDir / "seed.txt", "modified in place")
    val listener = new RecordingListener
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener)
      ):
        val _ = stage("write code")("done")
    assert(
      stashList(workDir).nonEmpty,
      "a corrupt own log must defeat skip-branch mode's dirty-tree tolerance"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      !steps.exists(_.contains("leaving")),
      s"nothing may be reported as left in place: $steps"
    )

  test(
    "skip-branch mode, RESUME with a dirty tree from an interrupted stage: still stashes, the stage re-runs clean"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-resume-dirty"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-work"))
    // A committed header, as if a prior run got this far before being killed.
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("my-work")),
        branch = branchName("my-work"),
        branchMode = BranchMode.Reused,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    // The interrupted stage's leftover: untracked, never committed.
    os.write(workDir / "partial.txt", "half-finished work")
    val listener = new RecordingListener
    var sawPartialInBody = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener)
      ):
        val _ = stage("task"):
          sawPartialInBody = os.exists(workDir / "partial.txt")
          "done"
    assert(
      !sawPartialInBody,
      "resume must stash the interrupted stage's leftover, so the stage re-runs against a clean tree"
    )
    val stashes = stashList(workDir)
    assert(
      stashes.nonEmpty,
      s"resume must still auto-stash a dirty tree: $stashes"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("stashed pending changes")),
      s"a stash-recovery Step must be emitted on resume: $steps"
    )

  test(
    "normal mode, FRESH run with --keep-changes: a modified tracked file and an untracked file survive branch creation and are swept into the stage commit"
  ):
    val workDir = GitRepo.seeded() // commits "seed.txt"
    val prompt = "keep-changes-fresh"
    val git = new OsGitTool(workDir)
    os.write.over(workDir / "seed.txt", "modified in place")
    os.write(workDir / "docs" / "plan.md", "plan notes", createFolders = true)
    val listener = new RecordingListener
    var bothPresentInBody = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt, target = RunTarget.NewBranch(Uncommitted.Keep)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener)
      ):
        val _ = stage("write code"):
          bothPresentInBody =
            os.read(workDir / "seed.txt") == "modified in place" &&
              os.exists(workDir / "docs" / "plan.md")
          "done"
    assert(
      bothPresentInBody,
      "both leftovers must still be in the tree when the stage body runs — not stashed away"
    )
    assertEquals(
      stashList(workDir),
      Nil,
      "a fresh run with --keep-changes must never stash"
    )
    assertNotEquals(
      git.currentBranch(),
      "main",
      "normal mode must still create a feature branch"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("leaving 2 uncommitted/untracked file(s)")),
      s"expected the leftover-file notice: $steps"
    )
    // Pin WHICH commit the leftovers land in: `git checkout -b` carried them
    // onto the new branch, and the header commit (HEAD~2) is pathspec-scoped,
    // so they reach the branch only via the first stage's commit (HEAD~1).
    val progressRelPath =
      s".orca/runs/${RunKey.of(prompt).value}.progress.json"
    assertEquals(commitMessage(workDir, "HEAD~2"), "orca: progress log")
    assertEquals(
      commitFiles(workDir, "HEAD~2"),
      List(progressRelPath),
      "the header commit must carry ONLY the progress log"
    )
    assertEquals(commitMessage(workDir, "HEAD~1"), "stage: write code")
    assertEquals(
      commitFiles(workDir, "HEAD~1"),
      List(progressRelPath, "docs/plan.md", "seed.txt"),
      "the first stage commit must carry both leftovers"
    )

  test(
    "normal mode, RESUME with --keep-changes: the flag is ignored — the interrupted stage's leftover is still stashed"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "keep-changes-resume"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("feat/keep-changes-resume"))
    // A committed header, as if a prior run got this far before being killed.
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/keep-changes-resume"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    // The interrupted stage's leftover: untracked, never committed.
    os.write(workDir / "partial.txt", "half-finished work")
    val listener = new RecordingListener
    var sawPartialInBody = false
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt, target = RunTarget.NewBranch(Uncommitted.Keep)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = List(listener)
      ):
        val _ = stage("task"):
          sawPartialInBody = os.exists(workDir / "partial.txt")
          "done"
    assert(
      !sawPartialInBody,
      "resume must stash the leftover despite --keep-changes, so the stage re-runs against a clean tree"
    )
    assert(
      stashList(workDir).nonEmpty,
      "resume must still auto-stash a dirty tree"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("ignoring --keep-changes")),
      s"resume must say the flag was ignored: $steps"
    )

  /** [[setupForSettings]] with the dirty-tree prompt injected: `answer` is what
    * the user "types", `tty` whether there is a terminal to type at. Counts the
    * asks, and records the emitted events, for the assertions that care.
    */
  private def setupAsking(
      workDir: os.Path,
      prompt: String,
      answer: DirtyTreeChoice,
      tty: Boolean = true,
      emitted: AtomicReference[List[OrcaEvent]] = new AtomicReference(Nil),
      asks: AtomicInteger = new AtomicInteger(0)
  ): FlowLifecycle.FlowSetup =
    setupForSettings(
      workDir,
      settingsOverride = Some(StackSettings.empty),
      prompt = prompt,
      emit = e => { val _ = emitted.updateAndGet(e :: _) },
      tty = () => tty,
      ask = _ => { val _ = asks.incrementAndGet(); answer }
    )

  test(
    "fresh run on a dirty tree, keep answered at the prompt: the files stay, nothing is stashed"
  ):
    val workDir = GitRepo.seeded()
    os.write(workDir / "handoff.md", "the plan")
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val setup =
      setupAsking(
        workDir,
        "prompt-keep",
        DirtyTreeChoice.Keep,
        emitted = emitted
      )
    assertEquals(setup.startingTree, StartingTree.Kept(None))
    assertEquals(stashList(workDir), Nil)
    assert(os.exists(workDir / "handoff.md"))
    val steps =
      emitted.get().reverse.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("leaving 1 uncommitted/untracked file")),
      s"expected the leftover-file notice: $steps"
    )

  test(
    "fresh run on a dirty tree, stash answered at the prompt: the tree is stashed clean"
  ):
    val workDir = GitRepo.seeded()
    os.write(workDir / "handoff.md", "the plan")
    val asks = new AtomicInteger(0)
    val setup =
      setupAsking(workDir, "prompt-stash", DirtyTreeChoice.Stash, asks = asks)
    assertEquals(asks.get(), 1, "the prompt must have been consulted")
    assertEquals(setup.startingTree, StartingTree.Clean)
    assert(stashList(workDir).nonEmpty, "the answer must reach the stash")
    assert(!os.exists(workDir / "handoff.md"))

  test(
    "fresh run on a dirty tree, abort answered at the prompt: refused with the tree and branches untouched"
  ):
    val workDir = GitRepo.seeded()
    os.write(workDir / "handoff.md", "the plan")
    os.write.over(workDir / "seed.txt", "modified in place")
    val branchesBefore = branchNames(workDir)
    val thrown = intercept[orca.OrcaFlowException]:
      val _ = setupAsking(workDir, "prompt-abort", DirtyTreeChoice.Abort)
    assert(
      thrown.getMessage.contains("refusing to start") &&
        thrown.getMessage.contains("--keep-changes"),
      s"the refusal must name a way out: ${thrown.getMessage}"
    )
    assertEquals(stashList(workDir), Nil, "the abort must precede any stash")
    assertEquals(os.read(workDir / "seed.txt"), "modified in place")
    assert(os.exists(workDir / "handoff.md"))
    assertEquals(
      branchNames(workDir),
      branchesBefore,
      "the abort must precede any branch mutation"
    )

  test(
    "fresh run on a dirty tree with no terminal: stashed without a prompt"
  ):
    val workDir = GitRepo.seeded()
    os.write(workDir / "handoff.md", "the plan")
    val asks = new AtomicInteger(0)
    val setup = setupAsking(
      workDir,
      "prompt-headless",
      DirtyTreeChoice.Keep,
      tty = false,
      asks = asks
    )
    assertEquals(asks.get(), 0, "a headless run must never prompt")
    assertEquals(setup.startingTree, StartingTree.Clean)
    assert(stashList(workDir).nonEmpty)

  test(
    "normal mode with --keep-changes: a failure before any stage commit keeps the kept untracked file and restores the kept tracked modification"
  ):
    val workDir = GitRepo.seeded() // commits "seed.txt" holding "seed"
    os.write(workDir / "handoff.md", "the plan")
    os.write.over(workDir / "seed.txt", "modified in place")
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, "keep-changes-teardown", target = keepChanges):
        val _ = stage[String]("crash"):
          os.write.over(workDir / "seed.txt", "partial edit")
          throw new RuntimeException("boom body")
    assert(os.exists(workDir / "handoff.md"))
    assertEquals(os.read(workDir / "seed.txt"), "modified in place")

  test(
    "normal mode with --keep-changes: a failure after a stage committed the kept modification leaves the committed content"
  ):
    // HEAD has moved past the snapshot's base, so the snapshot must not be
    // re-applied over the stage's commit.
    val workDir = GitRepo.seeded()
    val listener = new RecordingListener
    os.write.over(workDir / "seed.txt", "modified in place")
    val _ = intercept[ReportedFailure]:
      runFlowForTest(
        workDir,
        "keep-changes-committed",
        extraListeners = List(listener),
        target = keepChanges
      ):
        val _ = stage("rewrite"):
          os.write.over(workDir / "seed.txt", "rewritten by the stage")
          "done"
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom body")
    assertEquals(os.read(workDir / "seed.txt"), "rewritten by the stage")
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("are in the run's commits")),
      s"the snapshot must be named for manual recovery: $steps"
    )
    assert(!steps.exists(_.contains("could not restore")), steps)

  test(
    "failure teardown leaves the working tree alone when the body moved HEAD off the feature branch"
  ):
    val workDir = GitRepo.seeded()
    val listener = new RecordingListener
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, "moved-head", extraListeners = List(listener)):
        val _ = stage[String]("crash"):
          val _ = os
            .proc("git", "checkout", "-q", "-b", "elsewhere")
            .call(cwd = workDir)
          os.write.over(workDir / "seed.txt", "edited elsewhere")
          throw new RuntimeException("boom body")
    assertEquals(
      new OsGitTool(workDir).head(),
      Head.OnBranch(branchName("elsewhere"))
    )
    assertEquals(os.read(workDir / "seed.txt"), "edited elsewhere")
    val steps = listener.events.collect { case s: OrcaEvent.Step => s.message }
    assert(
      steps.exists(_.contains("leaving the working tree as it is")),
      s"expected a warning that teardown skipped the reset: $steps"
    )

  test(
    "normal mode with --keep-changes, no stage: success teardown leaves the kept file uncommitted and out of every commit"
  ):
    // The body commits nothing, so the kept file is still uncommitted when
    // success teardown runs — its log-removal commit is pathspec-scoped and
    // must not sweep the file in.
    val workDir = GitRepo.seeded()
    val prompt = "keep-changes-no-stage"
    val git = new OsGitTool(workDir)
    os.write(workDir / "handoff.md", "the plan")
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt, target = RunTarget.NewBranch(Uncommitted.Keep)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        summon[FlowContext].emit(OrcaEvent.Step("no-op"))
    assertEquals(
      git.dirtyPaths(),
      List("?? handoff.md"),
      "the kept file must still be uncommitted in the working tree"
    )
    val committedFiles = os
      .proc("git", "log", "--all", "--name-only", "--pretty=format:")
      .call(cwd = workDir)
      .out
      .text()
    assert(
      !committedFiles.linesIterator.map(_.trim).contains("handoff.md"),
      s"no commit may carry the kept file: $committedFiles"
    )

  test(
    "skip-branch mode: success teardown never deletes the reused branch, even with only orca commits"
  ):
    // Mirrors R5 (throwaway-branch auto-delete on a normal fresh run), but the
    // reused branch must survive: it is `BranchMode.Reused`, which
    // `ThrowawayBranch` never treats as throwaway.
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-throwaway"
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("my-empty-work"))
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args =
          OrcaArgs(prompt, target = RunTarget.CurrentBranch(Uncommitted.Stash)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        // body does nothing — no code changes, only orca's own commits land
        summon[orca.FlowContext].emit(OrcaEvent.Step("no-op"))
    assertEquals(git.currentBranch(), "my-empty-work")
    assertEquals(branchNames(workDir), Set("main", "my-empty-work"))

  test(
    "teardownSuccess: branchMode = Reused blocks the throwaway auto-delete even when the diff is blank"
  ):
    // Direct FlowSetup construction simulates the hazard: a tampered header's
    // `startingCommit` (unlike `branch`, never cross-checked against anything)
    // could name a commit that happens to diff-blank against the feature
    // branch — which is exactly R5's throwaway signature. `branchMode = Reused`
    // (skip-branch mode) must block the delete regardless of what
    // `startingCommit` claims.
    val workDir = GitRepo.seeded() // "main"
    val git = new OsGitTool(workDir)
    val startedAt = git.headCommit()
    val store = ProgressStore.default(workDir, RunKey.of("gated-delete"))
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ =
      git.createBranch(branchName("reused-branch")) // diff-blank vs "main"
    val featureBranch =
      FeatureBranch
        .resolveReused(branchName("reused-branch"), Set.empty)
        .toOption
        .get
    val setup = FlowLifecycle.FlowSetup(
      store = store,
      sessionStore = scratchSessions(),
      featureBranch = featureBranch,
      startingHead = Head.OnBranch(branchName("main")),
      stackSettings = StackSettings.empty,
      branchMode = BranchMode.Reused,
      startingTree = StartingTree.Clean,
      startingCommit = startedAt,
      worktree = None
    )
    FlowLifecycle.teardownSuccess(
      git,
      setup,
      PublishedState.NotPublished,
      _ => ()
    )
    assert(
      branchNames(workDir).contains("reused-branch"),
      "the reused branch must survive teardown when orca did not create it"
    )

  /** A repo on a `feat/work` branch orca created, with `code` committed there
    * when `withCode`, and the [[FlowLifecycle.FlowSetup]] teardown expects.
    */
  private def handoffFixture(
      withCode: Boolean
  ): (OsGitTool, os.Path, FlowLifecycle.FlowSetup) =
    val workDir = GitRepo.seeded() // "main"
    val git = new OsGitTool(workDir)
    val startedAt = git.headCommit()
    val store = ProgressStore.default(workDir, RunKey.of("handoff"))
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("feat/work"))
    if withCode then
      os.write(workDir / "code.txt", "real code")
      git.forceCommitOnly(workDir / "code.txt", "work")
    val setup = FlowLifecycle.FlowSetup(
      store = store,
      sessionStore = scratchSessions(),
      featureBranch = FeatureBranch
        .resolveReused(branchName("feat/work"), Set.empty)
        .toOption
        .get,
      startingHead = Head.OnBranch(branchName("main")),
      stackSettings = StackSettings.empty,
      branchMode = BranchMode.Created,
      startingTree = StartingTree.Clean,
      startingCommit = startedAt,
      worktree = None
    )
    (git, workDir, setup)

  test("teardownSuccess with StayPut leaves HEAD on the feature branch"):
    val (git, _, setup) = handoffFixture(withCode = true)
    FlowLifecycle.teardownSuccess(
      git,
      setup,
      PublishedState.NotPublished,
      _ => ()
    )
    assertEquals(git.currentBranch(), "feat/work")

  test(
    "teardownSuccess keeps an empty branch the run published from and returns to the start branch"
  ):
    // What was published points at what was pushed, so the branch has to stay
    // even though it carries nothing but orca's log against the start branch.
    val (git, workDir, setup) = handoffFixture(withCode = false)
    FlowLifecycle.teardownSuccess(
      git,
      setup,
      handoffPublished,
      _ => ()
    )
    assert(branchNames(workDir).contains("feat/work"), branchNames(workDir))
    assertEquals(git.currentBranch(), "main")

  test("teardownSuccess deletes a throwaway branch when nothing was published"):
    // Nothing but orca bookkeeping landed on the branch and there is nothing
    // published to answer, so it goes. `StayPut` is the only handoff this
    // pairs with — an unpublished run — and landing on `main` can only be the
    // delete's doing; the test above covers the other side.
    val (git, workDir, setup) = handoffFixture(withCode = false)
    FlowLifecycle.teardownSuccess(
      git,
      setup,
      PublishedState.NotPublished,
      _ => ()
    )
    assertEquals(git.currentBranch(), "main")
    assertEquals(branchNames(workDir), Set("main"))

  test(
    "teardownSuccess keeps an empty branch when the starting commit is gone"
  ):
    // A resume whose recorded commit is no longer an ancestor of HEAD has
    // nothing to measure the branch against.
    val (git, workDir, setup) = handoffFixture(withCode = false)
    FlowLifecycle.teardownSuccess(
      git,
      setup.copy(startingCommit = None),
      PublishedState.NotPublished,
      _ => ()
    )
    assert(branchNames(workDir).contains("feat/work"), branchNames(workDir))

  test(
    "a run whose progress log cannot be read keeps the branch it may have published from"
  ):
    // Both links of the Unknown arm in one run: a log that does not parse
    // classifies as Unknown rather than NotPublished, and Unknown blocks the
    // throwaway auto-delete on a branch carrying nothing but orca's own
    // commits — the log may have recorded published work, and the branch it
    // was pushed from cannot be recovered.
    val workDir = GitRepo.seeded()
    val prompt = "corrupt-log-throwaway"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    var featureBranch = ""
    runFlowForTest(workDir, prompt):
      featureBranch = summon[FlowContext].git.currentBranch()
      val _ = stage("no-op"):
        "done"
      os.write.over(store.path, "not json")
    assert(
      branchNames(workDir).contains(featureBranch),
      s"'$featureBranch' must survive teardown: ${branchNames(workDir)}"
    )

  private val TeardownPushBranch = "teardown-push-branch"

  /** Fixture for the teardown push gate: the repo, its bare local-path origin,
    * and the setup teardown expects. The progress log is written but NOT
    * committed, and the branch is NOT pushed — the gate turns on the order of
    * those two steps, so each test drives them itself via [[commitLog]] and
    * `git.push()`.
    */
  private case class TeardownPushRepo(
      git: OsGitTool,
      remote: os.Path,
      setup: FlowLifecycle.FlowSetup,
      logRelPath: os.SubPath
  ):
    /** Commit the progress log exactly as the runtime does. */
    def commitLog()(using WorkspaceWrite): Unit =
      git.forceCommitOnly(setup.store.path, "orca: progress log")

  /** A bare local-path remote needs no credentials, so these tests exercise the
    * real push without a network round-trip.
    */
  private def teardownPushFixture(): TeardownPushRepo =
    val workDir = GitRepo.seeded() // "main"
    val git = new OsGitTool(workDir)
    val store = ProgressStore.default(workDir, RunKey.of("teardown-push"))
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName(TeardownPushBranch))
    os.write.over(store.path, "# progress\n", createFolders = true)
    val remote = TempDirs.dir() / "remote.git"
    val _ =
      os.proc("git", "init", "--bare", remote.toString).call(cwd = workDir)
    val _ = os
      .proc("git", "remote", "add", "origin", remote.toString)
      .call(cwd = workDir)
    val setup = FlowLifecycle.FlowSetup(
      store = store,
      sessionStore = scratchSessions(),
      featureBranch = FeatureBranch
        .resolveReused(branchName(TeardownPushBranch), Set.empty)
        .toOption
        .get,
      startingHead = Head.OnBranch(branchName("main")),
      stackSettings = StackSettings.empty,
      branchMode = BranchMode.Reused,
      startingTree = StartingTree.Clean,
      startingCommit = None,
      worktree = None
    )
    TeardownPushRepo(git, remote, setup, store.path.subRelativeTo(workDir))

  private def remoteRefs(remote: os.Path): String =
    os.proc("git", "for-each-ref", "--format=%(refname)")
      .call(cwd = remote)
      .out
      .text()

  private def remoteTip(remote: os.Path): String =
    os.proc("git", "rev-parse", TeardownPushBranch)
      .call(cwd = remote)
      .out
      .text()

  private def remoteFiles(remote: os.Path): String =
    os.proc("git", "ls-tree", "-r", "--name-only", TeardownPushBranch)
      .call(cwd = remote)
      .out
      .text()

  test("teardownSuccess pushes the progress-log removal to a pushed branch"):
    val repo = teardownPushFixture()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    repo.commitLog()
    repo.git.push().orThrow
    FlowLifecycle
      .teardownSuccess(
        repo.git,
        repo.setup,
        PublishedState.NotPublished,
        _ => ()
      )
    val files = remoteFiles(repo.remote)
    assert(!files.linesIterator.contains(repo.logRelPath.toString), files)

  test("teardownSuccess pushes nothing when the branch has no upstream"):
    val repo = teardownPushFixture()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    repo.commitLog()
    FlowLifecycle
      .teardownSuccess(
        repo.git,
        repo.setup,
        PublishedState.NotPublished,
        _ => ()
      )
    assertEquals(remoteRefs(repo.remote).trim, "")

  test("teardownSuccess pushes nothing when the upstream never got the log"):
    // The discriminating case: the branch has an upstream (a --skip-branch run
    // can inherit one), but the log was only ever committed locally — so the
    // run never published it, and teardown must not push the local commits.
    val repo = teardownPushFixture()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    repo.git.push().orThrow
    val before = remoteTip(repo.remote)
    repo.commitLog()
    FlowLifecycle
      .teardownSuccess(
        repo.git,
        repo.setup,
        PublishedState.NotPublished,
        _ => ()
      )
    assertEquals(remoteTip(repo.remote), before)

  /** The closing block's Step messages, in emission order, from a direct
    * `teardownSuccess` over `setup`.
    */
  private def closingSummary(
      git: OsGitTool,
      setup: FlowLifecycle.FlowSetup,
      published: PublishedState
  ): List[String] =
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    FlowLifecycle.teardownSuccess(
      git,
      setup,
      published,
      e => { val _ = emitted.updateAndGet(e :: _) }
    )
    emitted.get().reverse.collect { case s: OrcaEvent.Step => s.message }

  private def closingSetup(
      store: ProgressStore,
      branch: String,
      branchMode: BranchMode,
      startingCommit: Option[orca.gitref.CommitHash]
  ): FlowLifecycle.FlowSetup =
    FlowLifecycle.FlowSetup(
      store = store,
      sessionStore = scratchSessions(),
      featureBranch =
        FeatureBranch.resolveReused(branchName(branch), Set.empty).toOption.get,
      startingHead = Head.OnBranch(branchName("main")),
      stackSettings = StackSettings.empty,
      branchMode = branchMode,
      startingTree = StartingTree.Clean,
      startingCommit = startingCommit,
      worktree = None
    )

  test(
    "closing summary: HEAD staying on the feature branch gets the plain base diff"
  ):
    // The default path: the branch the count was taken on is the one the user
    // is left on, so `git diff <base>` there already shows the work.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val base = git.headCommit().get
    val _ = git.createBranch(branchName("closing-stay"))
    os.write(workDir / "a.txt", "one")
    os.write(workDir / "b.txt", "two")
    assert(git.commit("the run's work").isRight)
    val setup = closingSetup(
      ProgressStore.default(workDir, RunKey.of("closing-stay")),
      "closing-stay",
      BranchMode.Created,
      Some(base)
    )
    assertEquals(
      closingSummary(git, setup, PublishedState.NotPublished),
      List(
        "done — you are on branch 'closing-stay'",
        s"2 file(s) changed since ${base.short}",
        s"next: git diff ${base.short}"
      )
    )

  test(
    "closing summary: a published run names the reference and points the diff at the work"
  ):
    // Both halves of the straddle in one run: the branch line is read after the
    // handoff (so it says 'main'), while the count and the command are taken on
    // the feature branch — a `git diff <base>` on 'main' would print nothing.
    // The published line is the only difference from the unpublished runs
    // below.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val base = git.headCommit().get
    val _ = git.createBranch(branchName("closing-work"))
    os.write(workDir / "a.txt", "one")
    os.write(workDir / "b.txt", "two")
    assert(git.commit("the run's work").isRight)
    val setup = closingSetup(
      ProgressStore.default(workDir, RunKey.of("closing-work")),
      "closing-work",
      BranchMode.Created,
      Some(base)
    )
    assertEquals(
      closingSummary(git, setup, handoffPublished),
      List(
        "done — you are on branch 'main'",
        s"published at ${handoffPr.url}",
        s"2 file(s) changed since ${base.short}",
        s"next: git diff ${base.short}..closing-work"
      )
    )

  test(
    "closing summary: a throwaway branch is deleted first, so the block names the branch the user is left on"
  ):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val base = git.headCommit().get
    val _ =
      git.createBranch(branchName("closing-throwaway")) // no user code on it
    val setup = closingSetup(
      ProgressStore.default(workDir, RunKey.of("closing-throwaway")),
      "closing-throwaway",
      BranchMode.Created,
      Some(base)
    )
    assertEquals(
      closingSummary(git, setup, PublishedState.NotPublished),
      List("done — you are on branch 'main'", "no files changed")
    )

  test("closing summary: no recorded starting commit omits the file count"):
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("closing-no-base"))
    os.write(workDir / "a.txt", "one")
    assert(git.commit("the run's work").isRight)
    val setup = closingSetup(
      ProgressStore.default(workDir, RunKey.of("closing-no-base")),
      "closing-no-base",
      BranchMode.Reused,
      startingCommit = None
    )
    assertEquals(
      closingSummary(git, setup, PublishedState.NotPublished),
      List("done — you are on branch 'closing-no-base'")
    )

  test(
    "resume: setup names the branch it bound and what the replay carries over"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "resume-announcement"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val base = git.headCommit().get
    val _ = git.createBranch(branchName("resumed-work"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("resumed-work"),
        branchMode = BranchMode.Created,
        startingCommit = base,
        userPrompt = prompt,
        flow = None
      )
    )
    store.upsertEntry(
      StageEntry(
        id = StagePath.FlowBody.child("plan", 0),
        resultJson = RawJson("\"done\"")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val head = git.headCommit().get
    // No settings file, so this resume arm rediscovers and commits one, moving
    // HEAD: naming the pre-settings commit is what pins the announcement ahead
    // of orca's own bookkeeping.
    val canned = StackDiscoveryResult(
      format = DiscoveredGate(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredGate(),
      test = DiscoveredGate()
    )
    val (_, steps) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), prompt)
    assertNotEquals(
      git.headCommit(),
      Some(head),
      "the fixture must move HEAD, or the ordering isn't exercised"
    )
    assert(
      steps.contains(
        s"on branch 'resumed-work' — this run started from ${base.short}"
      ),
      s"the resumed arm must name its branch: $steps"
    )
    assert(
      steps.contains(
        s"resuming: 1 stage(s) already recorded, tree at ${head.short}; " +
          "the interrupted stage's uncommitted work was not carried over"
      ),
      s"the resumed arm must say what it carries over: $steps"
    )

  test(
    "resume after a skip-branch run on a mixed-case/slashed branch name works"
  ):
    // `validateHeader` must not require the minted-name slug shape: a
    // reused branch is accepted because it equals the current branch (R30's
    // cross-check).
    val workDir = GitRepo.seeded()
    val prompt = "skip-branch-resume"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val invocations = new AtomicInteger(0)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val git = new OsGitTool(workDir)
    val _ = git.createBranch(branchName("Feature/JIRA-123"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("Feature/JIRA-123"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = GitRepo.headCommit(workDir)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.upsertEntry(
      StageEntry(
        id = StagePath.FlowBody.child("resumable-stage", 0),
        resultJson = RawJson("\"ok\"")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: resumable-stage")

    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("resumable-stage"):
          invocations.incrementAndGet()
          "ok"

    assertEquals(
      invocations.get(),
      0,
      "resumed run must not re-run the already-recorded stage"
    )
    assertEquals(
      git.currentBranch(),
      "main",
      "success teardown returns to the header's recorded start branch"
    )

  test(
    "normal mode, RESUME with a tracked-but-modified (JSON-broken) progress log: the stash reverts it to committed content first, so resume proceeds rather than misreading it as corrupt"
  ):
    // Regression for the skip-branch dirty-tree change: `setup` must decide
    // fresh-vs-resume from the log's last COMMITTED content in every mode,
    // never a dirty read — otherwise a user's broken in-progress edit to the
    // (tracked) log file could flip a resumable run to "Corrupt → fresh",
    // discarding already-recorded progress.
    val workDir = GitRepo.seeded()
    val prompt = "resume-dirty-json-break"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val invocations = new AtomicInteger(0)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val git = new OsGitTool(workDir)
    val _ = git.createBranch(branchName("feat/resume-dirty-json-break"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/resume-dirty-json-break"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.upsertEntry(
      StageEntry(
        id = StagePath.FlowBody.child("resumable-stage", 0),
        resultJson = RawJson("\"ok\"")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: resumable-stage")
    // An uncommitted edit that breaks the JSON — a broken hand-edit or a torn
    // write, left dirty in the already-tracked log file.
    os.write.over(store.path, "not json {{{")

    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("resumable-stage"):
          invocations.incrementAndGet()
          "ok"

    assertEquals(
      invocations.get(),
      0,
      "resume must read the stashed-clean committed log, not treat the " +
        "dirty JSON-break as corrupt and re-run the already-recorded stage"
    )

  test(
    "normal mode, --keep-changes with a tracked-but-modified (JSON-broken) progress log: the log is still stashed clean, so the run resumes rather than starting fresh"
  ):
    // The flag may not defeat the committed-content classification: the
    // pre-stash peek reads the broken copy as `Corrupt`, which must still take
    // the stash arm — otherwise the run would mint a new branch and re-run
    // every completed stage over the interrupted stage's leftovers.
    val workDir = GitRepo.seeded()
    val prompt = "keep-changes-dirty-json-break"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val invocations = new AtomicInteger(0)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val git = new OsGitTool(workDir)
    val _ = git.createBranch(branchName("feat/keep-changes-dirty-json-break"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/keep-changes-dirty-json-break"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.upsertEntry(
      StageEntry(
        id = StagePath.FlowBody.child("resumable-stage", 0),
        resultJson = RawJson("\"ok\"")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: resumable-stage")
    os.write.over(store.path, "not json {{{")

    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(prompt, target = RunTarget.NewBranch(Uncommitted.Keep)),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        val _ = stage("resumable-stage"):
          invocations.incrementAndGet()
          "ok"

    assertEquals(
      invocations.get(),
      0,
      "--keep-changes must not turn a dirty JSON-break into a fresh run that " +
        "re-runs the already-recorded stage"
    )

  test(
    "surfaced: a setup resume-refusal reaches the user as one Error and escapes as ReportedFailure"
  ):
    // A header written for another prompt makes `setup` throw the resume
    // refusal. It must reach the user's event surface exactly once and escape
    // marked as surfaced.
    val workDir = GitRepo.seeded()
    val prompt = "surfaced-tampered"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("feat/surfaced-tampered"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("feat/surfaced-tampered"),
        branchMode = BranchMode.Created,
        userPrompt = "a different prompt",
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt, extraListeners = List(listener)):
        val _ = stage("never-runs")("x")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assert(
      errors.head.message.contains("refusing to resume"),
      s"the refusal message must reach the user: ${errors.head.message}"
    )
    assert(
      thrown.cause.isInstanceOf[orca.OrcaFlowException],
      s"the surfaced cause must be the original refusal: ${thrown.cause}"
    )
    // The header-validation failure reason rides along in the same message.
    assert(
      thrown.cause.getMessage.contains("failed validation"),
      s"abort message must mention validation failure: ${thrown.cause.getMessage}"
    )

  test(
    "surfaced: a resume header naming a protected branch is refused end-to-end through setup"
  ):
    // Unlike the tampered-prompt fixture above, this one names a
    // genuinely protected branch (`master`) as the header's feature branch —
    // exercising the OTHER `validateHeader` failure mode end-to-end through
    // `FlowLifecycle.setup`, not just at the `RecoveryCheckTest` unit level.
    val workDir = GitRepo.seeded()
    val prompt = "resume-protected-branch"
    val store = ProgressStore.default(workDir, RunKey.of(prompt))
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(branchName("feat/resume-protected-branch"))
    store.writeHeader(
      ProgressHeader(
        startingBranch = Some(branchName("main")),
        branch = branchName("master"),
        branchMode = BranchMode.Created,
        userPrompt = prompt,
        flow = None,
        startingCommit = unreachableCommit
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt, extraListeners = List(listener)):
        val _ = stage("never-runs")("x")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assert(
      errors.head.message.contains("refusing to resume"),
      s"the refusal message must reach the user: ${errors.head.message}"
    )
    assert(
      thrown.cause.getMessage.contains("protected branch"),
      s"abort message must name the protected branch: ${thrown.cause.getMessage}"
    )

  test(
    "surfaced: a body failure whose teardownFailure ALSO throws surfaces once; the reset failure rides along suppressed"
  ):
    // The body reports its Error at the stage boundary (one Error, no
    // double-report). failure teardown (`discardUncommitted`) still runs — and
    // if the reset itself throws, that must NOT mask the original body failure:
    // it is attached as suppressed so the user sees the body message and debug
    // sees both.
    val workDir = GitRepo.seeded()
    val prompt = "surfaced-suppressed"
    val listener = new RecordingListener
    val thrown = intercept[ReportedFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          FlowHarness.request(
            args = OrcaArgs(prompt),
            stackSettings = Some(StackSettings.empty),
            workDir = workDir,
            interaction = Some(interaction),
            extraListeners = List(listener),
            branchNaming = None,
            wiring = FlowWiring(
              claude = Some(_ => StubAgent.claude),
              git = Some(new ResetThrowingGit(workDir))
            )
          )
        ):
          val _ = stage[String]("crash"):
            throw new RuntimeException("boom body")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assertEquals(thrown.cause.getMessage, "boom body")
    assert(
      thrown.getSuppressed.exists(_.getMessage.contains("reset boom")),
      s"the failing reset must be suppressed on the thrown failure: " +
        thrown.getSuppressed.mkString(", ")
    )
    // The reset failure ALSO gets a user-visible note (in addition to, not
    // instead of, the suppressed exception above).
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(_.message.contains("workspace reset failed")),
      s"a Step warning about the failed reset must be emitted: $steps"
    )

  test(
    "a body failure emits an explanatory Step before the reset --hard teardown runs"
  ):
    // The reset's own Step ("Discarded uncommitted changes") reads as
    // unexplained data loss on its own — FlowLifecycle.run must emit a
    // preceding Step naming WHY the reset is about to happen (recovery, not
    // loss) whenever a body failure triggers failure teardown.
    val workDir = GitRepo.seeded()
    val prompt = "explains-reset-teardown"
    val listener = new RecordingListener
    val _ = intercept[ReportedFailure]:
      runFlowForTest(workDir, prompt, extraListeners = List(listener)):
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom body")
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(
        _.message.contains("recovering from the failure")
      ),
      s"expected an explanatory Step ahead of the reset: $steps"
    )
    assert(
      steps.exists(_.message.contains("re-run the same command")),
      s"the recovery Step must name what resumes the run: $steps"
    )

  // --- flow() reentrancy/concurrency guards --------------------------------

  test(
    "reentrancy guards: a nested flow() is refused before it creates anything, and the outer flow is unaffected"
  ):
    val workDir = GitRepo.seeded()
    var innerThrown: Option[Throwable] = None
    // The body catches the refusal, so the outer `flow()` succeeds; were it to
    // fail, its `System.exit(1)` would end this whole forked test JVM.
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs("nested-guard"),
        stackSettings = Some(StackSettings.empty),
        claude = Some(_ => StubAgent.claude),
        workDir = workDir,
        interaction = Some(interaction)
      ):
        innerThrown =
          try
            flow(
              args = OrcaArgs("inner", target = RunTarget.Worktree),
              workDir = workDir,
              interaction = Some(interaction)
            )(())
            None
          catch case e: Throwable => Some(e)
    val thrown = innerThrown.getOrElse(fail("nested flow() must throw"))
    assert(thrown.isInstanceOf[orca.OrcaFlowException])
    assertEquals(thrown.getMessage, "a flow is already running in this process")
    assert(
      !os.exists(OrcaDir.worktreesPath(workDir)),
      "the nested flow() must not create its worktree"
    )
    // The outer flow ended cleanly back on the starting branch.
    val branch =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(branch, "main")

  test(
    "reentrancy guards: a workdir lock held by a live process refuses a new runFlow"
  ):
    val workDir = GitRepo.seeded()
    val holder = LockHolder.acquire(LockHolder.Lock.Workdir(workDir))
    try
      val thrown = intercept[orca.OrcaFlowException]:
        supervised:
          val interaction = TerminalInteraction.start(
            out = new PrintStream(new ByteArrayOutputStream()),
            useColor = false,
            animated = false
          )
          runFlow(
            FlowHarness.request(
              args = OrcaArgs("locked"),
              stackSettings = Some(StackSettings.empty),
              wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
              workDir = workDir,
              interaction = Some(interaction),
              extraListeners = Nil,
              branchNaming = None
            )
          ):
            ()
      // `intercept` pins the unwrapped type, not a `ReportedFailure`.
      assertEquals(
        thrown.getMessage,
        s"a flow is already running in this working tree (pid " +
          s"${holder.wrapped.pid()}) — wait for it to finish, or stop it"
      )
    finally LockHolder.kill(holder)

  test("reentrancy guards: the lock file is never swept into a stage commit"):
    // The stage runtime's commit is `git add -A` + a force-add of the progress
    // log only; the lock must stay out of history even though the temp repo
    // has no `.gitignore` for `.orca/` (the lock lives under `.orca/cache/`,
    // whose own `.gitignore` self-ignores everything in it).
    val workDir = GitRepo.seeded()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        FlowHarness.request(
          args = OrcaArgs("lock-not-committed"),
          stackSettings = Some(StackSettings.empty),
          wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None
        )
      ):
        val _ = stage[String]("write"):
          os.write(workDir / "out.txt", "data")
          "done"
    val everTracked = os
      .proc("git", "log", "--all", "--name-only", "--pretty=format:")
      .call(cwd = workDir)
      .out
      .text()
    assert(
      everTracked.contains("out.txt"),
      s"the stage's code change must be committed; history files: $everTracked"
    )
    assert(
      !everTracked.contains("flow.lock"),
      "the flow lock must never appear in any commit"
    )

  /** Records every `OrcaEvent` it sees, so the boundary-emission tests can
    * count how many `OrcaEvent.Error`s a failing run produced.
    */
  private class RecordingListener extends OrcaListener:
    private val seen = new AtomicReference[List[OrcaEvent]](Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  /** An `OsGitTool` whose `discardUncommitted` always throws — to exercise the
    * body-phase failure teardown throwing while it handles a body failure, so
    * the reset error is attached as suppressed rather than masking the
    * original.
    */
  private class ResetThrowingGit(workDir: os.Path) extends OsGitTool(workDir):
    override def discardUncommitted(untracked: UntrackedFiles)(using
        WorkspaceWrite
    ): Unit =
      throw new RuntimeException("reset boom")

end FlowLifecycleTest
