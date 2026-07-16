package orca.runner

import orca.util.RawJson
import orca.{
  BranchNamingStrategy,
  FlowContext,
  OrcaArgs,
  OrcaDir,
  StackSettings,
  WorkspaceWrite,
  runFlow,
  stage,
  flow
}
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{
  Agent,
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  JsonData,
  AgentCall,
  AgentConfig,
  Model,
  OpencodeAgent,
  PiAgent,
  SessionId,
  WireSessionId,
  ToolSet
}
import orca.backend.{IdScheme, SessionSupport}
import orca.progress.{ProgressHeader, ProgressStore, SessionRecord, StageEntry}
import orca.runner.terminal.TerminalInteraction
import orca.tools.{FsTool, GitHubTool, GitTool, OsGitTool}
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import orca.testkit.{GitRepo, TempDirs}

/** Tests for the flow lifecycle: success teardown, failure teardown, and resume
  * across two calls. Each test uses a real temp git repo via `GitRepo.seeded()`
  * and a null-sink `TerminalInteraction` so no TTY is required.
  *
  * The first three tests cover teardown/resume through the public `flow(...)`
  * and by hand-building state — `flow()` calls `System.exit(1)` on body
  * failure, so they can't drive a failing invocation directly.
  *
  * The last two tests exercise the genuine end-to-end crash→resume path via the
  * exit-free `runFlow(...)` seam: a body that throws in stage 2 propagates (no
  * `System.exit`), failure teardown keeps HEAD on the feature branch with stage
  * 1 recorded, and a second `runFlow` over the same store resumes — replaying
  * stage 1 instead of re-running it.
  */
class FlowLifecycleTest extends munit.FunSuite:

  test("success teardown: ends on start branch and removes progress-log file"):
    val workDir = GitRepo.seeded()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs("lifecycle-success"),
        stackSettings = Some(StackSettings.empty),
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction)
      ):
        summon[FlowContext].emit(OrcaEvent.Step("body ran"))
    // After flow returns, HEAD must be back on main (the starting branch).
    val branch =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(branch, "main")
    // The progress-log file must have been removed.
    val store = ProgressStore.default(workDir, "lifecycle-success")
    assert(!os.exists(store.path), s"progress log ${store.path} should be gone")

  test(
    "failure teardown: stays on feature branch with clean working tree and earlier commit present"
  ):
    // Build the pre-failure state manually: feature branch with a committed
    // progress header + one completed stage entry, then staged (but not yet
    // committed) partial work from a second stage.
    // Then apply failure teardown (resetHard) and assert the resulting state.
    val workDir = GitRepo.seeded()
    val git = new OsGitTool(workDir)
    val prompt = "lifecycle-failure"
    val store = ProgressStore.default(workDir, prompt)

    given WorkspaceWrite = WorkspaceWrite.unsafe

    // Mirror flowSetup: create feature branch, commit progress header.
    val _ = git.createBranch("feat/lifecycle-failure")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/lifecycle-failure",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")

    // Simulate stage-one completing: write and commit code + stage entry.
    os.write(workDir / "one.txt", "content")
    store.appendEntry(
      StageEntry("stage-one#0", "stage-one", RawJson("\"done\""))
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: stage-one")

    // Simulate stage-two leaving staged but uncommitted work.
    // Writing + staging makes this a modified-in-index file that `reset --hard`
    // will remove (unlike untracked files which reset --hard leaves alone).
    os.write(workDir / "two.txt", "partial")
    val _ = os.proc("git", "add", "two.txt").call(cwd = workDir)
    val featureBranch = git.currentBranch()

    // Apply failure teardown (git reset --hard, stay on feature branch).
    git.resetHard()

    // HEAD must still be on the feature branch.
    assertEquals(git.currentBranch(), featureBranch)
    // Working tree must be clean (staged partial work was discarded).
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
    // Stage one's result must survive in the progress log.
    val ids = store.load().get.entries.map(_.id)
    assert(ids.contains("stage-one#0"), "stage one must remain recorded")
    // Stage two's partial file must be gone (reset --hard wiped it from index).
    assert(
      !os.exists(workDir / "two.txt"),
      "staged partial file must be wiped by reset --hard"
    )

  test(
    "setup resume: second flow call resumes feature branch; a stage body runs only once"
  ):
    // Build the on-disk state that an aborted first run leaves: the feature
    // branch has a committed progress header + one completed stage entry, and
    // the progress-log file is present on disk (failure teardown stays on the
    // feature branch without deleting the log).
    val workDir = GitRepo.seeded()
    val prompt = "lifecycle-resume"
    val store = ProgressStore.default(workDir, prompt)
    val invocations = new AtomicInteger(0)

    given WorkspaceWrite = WorkspaceWrite.unsafe

    val git = new OsGitTool(workDir)
    val _ = git.createBranch("feat/lifecycle-resume")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/lifecycle-resume",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.appendEntry(
      StageEntry("resumable-stage#0", "resumable-stage", RawJson("\"ok\""))
    )
    git.forceAdd(store.path)
    val _ = git.commit("stage: resumable-stage")
    // We are on the feature branch (as failure teardown would leave us) and the
    // progress-log file is present on disk — flow() can read it via store.load().

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
        agent = _ => StubAgent.claude,
        workDir = workDir,
        progressStore = Some(store),
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
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()

    // The body failure escapes `runFlow` wrapped in `SurfacedFlowFailure` (it
    // was reported first); the original is its `cause`.
    val thrown = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store):
        val _ = stage("stage-one"):
          os.write(workDir / "one.txt", "content")
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom in stage two")
    assertEquals(thrown.cause.getMessage, "boom in stage two")

    // HEAD must be on the feature branch, not the start branch.
    val branch = git.currentBranch()
    assertNotEquals(branch, startBranch)
    assertEquals(branch, store.load().get.header.branch)

    // Stage one's commit + log entry must survive.
    val ids = store.load().get.entries.map(_.id)
    assert(ids.contains("stage-one#0"), "stage one must be recorded")
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
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()
    val stageOneRuns = new AtomicInteger(0)

    // First run: crashes in stage two.
    val _ = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store):
        val _ = stage("stage-one"):
          stageOneRuns.incrementAndGet()
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom")
    assertEquals(stageOneRuns.get(), 1, "stage one runs once in the first run")

    // Failure teardown leaves the repo on the feature branch — which is exactly
    // the resume entry point: a re-run "in place" (the next invocation inherits
    // the repo's HEAD, which the crash left on the feature branch) finds the
    // committed progress log in the working tree and resumes from it.
    val featureBranch = git.currentBranch()
    assertNotEquals(featureBranch, startBranch)

    // Second run from the feature branch: resumes; stage one is replayed from the
    // log (body skipped), stage two runs fresh.
    runFlowForTest(workDir, prompt, store):
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
    "runFlow does not double-report a plain exception that already surfaced at a stage"
  ):
    // A plain RuntimeException thrown inside a stage surfaces its Error at the
    // stage boundary; as it unwinds to the flow boundary, the reported-set (the
    // production DefaultFlowContext one) must suppress a second Error.
    val workDir = GitRepo.seeded()
    val prompt = "boundary-stage-once"
    val store = ProgressStore.default(workDir, prompt)
    val listener = new RecordingListener
    val _ = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store, extraListeners = List(listener)):
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")

  test("runFlow reports a body failure outside any stage exactly once"):
    // A body that throws directly (never entering a stage) is reported once at
    // the flow boundary itself.
    val workDir = GitRepo.seeded()
    val prompt = "boundary-body-once"
    val store = ProgressStore.default(workDir, prompt)
    val listener = new RecordingListener
    val _ = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store, extraListeners = List(listener)):
        throw new RuntimeException("boom outside any stage")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")

  test(
    "runFlow: a pre-ctx agent-factory failure escapes UNWRAPPED, not as SurfacedFlowFailure"
  ):
    // The `SurfacedFlowFailure` discriminator's other half: the `surfaced`
    // brackets only wrap lead resolution, setup, rehydration and the body. A
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
          args = OrcaArgs(prompt),
          stackSettings = Some(StackSettings.empty),
          agent = _ => StubAgent.claude,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = None,
          wiring = FlowWiring(claude =
            Some(_ => throw new RuntimeException("factory boom"))
          )
        ):
          ()
    assertEquals(thrown.getMessage, "factory boom")
    assert(
      !thrown.isInstanceOf[SurfacedFlowFailure],
      s"a pre-ctx factory failure must NOT be wrapped in SurfacedFlowFailure: $thrown"
    )

  test(
    "R20: snapshot-before-stash restores the log file if the stash removed it"
  ):
    // The end-to-end stash hazard is belt-and-suspenders (the log is normally
    // committed, so the stash can't remove it), so cover the helper directly:
    // a snapshot taken while the file exists restores its exact bytes after the
    // file is gone, and is a no-op when the file still exists.
    val dir = TempDirs.dir()
    val path = dir / ".orca" / "progress-x.json"
    os.write(path, "{\"header\":true}", createFolders = true)
    val snapshot = FlowLifecycle.snapshotLog(path)
    assert(snapshot.isDefined, "snapshot must capture an existing file")

    // Simulate the stash removing the file, then restore it.
    val _ = os.remove(path)
    FlowLifecycle.restoreLogIfMissing(path, snapshot)
    assert(os.exists(path), "log must be restored from the snapshot")
    assertEquals(os.read(path), "{\"header\":true}")

    // Restore is a no-op when the file is still present (does not overwrite).
    os.write.over(path, "untouched")
    FlowLifecycle.restoreLogIfMissing(path, snapshot)
    assertEquals(os.read(path), "untouched")

    // A snapshot of a missing file is None; restore then does nothing.
    val missing = dir / ".orca" / "absent.json"
    assertEquals(FlowLifecycle.snapshotLog(missing), None)
    FlowLifecycle.restoreLogIfMissing(missing, None)
    assert(!os.exists(missing))

  test(
    "R30: a log whose recorded branch differs from the current branch aborts"
  ):
    // Simulate a merged feature branch: the committed log records branch X, but
    // HEAD is on Y (as if X was merged into Y, carrying the log along). Resuming
    // must abort rather than replay against the wrong branch.
    val workDir = GitRepo.seeded()
    val prompt = "merged-hazard"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)

    given WorkspaceWrite = WorkspaceWrite.unsafe
    // Commit the log on `main` (HEAD) while it names a different feature branch.
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/merged-hazard",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val currentBranch = git.currentBranch()

    // The abort now surfaces first (reported to the user), then escapes wrapped
    // in `SurfacedFlowFailure`; the original `OrcaFlowException` is its `cause`.
    val thrown = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store):
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
    // "no log yet" absence). `loadDetailed()` returns `Corrupt`, and `setup`
    // must take the same fresh-run path an absent log would — resolve +
    // create a branch and commit a brand-new header — rather than throwing or
    // silently doing nothing. The "starting fresh" warning is now routed
    // through the threaded `emit` as an `OrcaEvent.Step` (so a Slack-backed
    // Interaction sees it, not just a terminal), which this test captures.
    val workDir = GitRepo.seeded()
    val prompt = "corrupt-log-fresh"
    val store = ProgressStore.default(workDir, prompt)
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
      settingsOverride = Some(StackSettings.empty),
      store = store,
      emit = e => { val _ = emitted.updateAndGet(e :: _) }
    )

    // The corrupt-log warning reached the event surface as a Step (a listener,
    // e.g. Slack, can observe it) — not only a stderr line.
    val steps = emitted.get().collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(_.message.contains("corrupt")),
      s"a Step warning about the corrupt log must be emitted: $steps"
    )

    // A fresh branch was resolved and created, distinct from the start branch
    // — not an abort, and not a no-op that leaves HEAD where it was.
    assertEquals(git.currentBranch(), setup.featureBranch.value)
    assertNotEquals(setup.featureBranch.value, startBranch)
    assertEquals(setup.startBranch, startBranch)
    // A brand-new header was written and committed — the corrupt bytes are
    // gone, replaced by a valid fresh log with no entries.
    val loaded = store.load()
    assert(loaded.isDefined, "a fresh header must have been written")
    assertEquals(loaded.get.header.branch, setup.featureBranch.value)
    assertEquals(loaded.get.entries, Nil)

  // Pinned ADR-0019 migration warning: names the settings path and the likely
  // gitignore line to remove.
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
    val store = ProgressStore.default(workDir, prompt)
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val _ = FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = StubAgent.claude,
      git = git,
      workDir = workDir,
      branchNaming = None,
      settingsOverride = settingsOverride,
      store = store,
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

  /** Drives `setup` directly against `workDir` with a throwaway store and a
    * null event sink — the fixture for the stack-settings resolution tests.
    */
  private def setupForSettings(
      workDir: os.Path,
      settingsOverride: Option[StackSettings] = None,
      prompt: String = "settings-resolution"
  ): FlowLifecycle.FlowSetup =
    FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = StubAgent.claude,
      git = new OsGitTool(workDir),
      workDir = workDir,
      branchNaming = None,
      settingsOverride = settingsOverride,
      store = ProgressStore.default(workDir, prompt),
      emit = _ => ()
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
      thrown.getMessage.contains("invalid stack settings") &&
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
      prompt: String
  ): (FlowLifecycle.FlowSetup, List[String]) =
    val emitted = new AtomicReference[List[OrcaEvent]](Nil)
    val setup = FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = agent,
      git = new OsGitTool(workDir),
      workDir = workDir,
      branchNaming = None,
      settingsOverride = None,
      store = ProgressStore.default(workDir, prompt),
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
    "setup: fresh arm, no file, no override — discovery gives the settings file its own commit, before the header commit"
  ):
    val workDir = GitRepo.seeded()
    val canned = StackDiscoveryResult(
      format = DiscoveredTask(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt", Some("seeded fixture")))
      ),
      lint = DiscoveredTask(unsetReason = Some("no lint config found")),
      test = DiscoveredTask()
    )
    val (setup, steps) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), "discover-fresh")
    // The discovered settings are the run's settings…
    assertEquals(setup.stackSettings, StackSettings(format = List("echo fmt")))
    // …and the written file is the byte-exact render of the checked entries.
    assertEquals(
      os.read(OrcaDir.settingsPath(workDir)),
      """# orca stack settings — edit freely, commit with the project.
        |# Delete this file to re-run auto-discovery.
        |# seed.txt; seeded fixture
        |format = echo fmt
        |# lint =   (no lint config found)
        |# test =   (no evidence found)
        |""".stripMargin
    )
    // The dedicated settings commit sits immediately before the header commit
    // (HEAD~1), carries EXACTLY the settings file, and bears the pinned message.
    assertEquals(
      commitFiles(workDir, "HEAD~1"),
      List(".orca/settings.properties")
    )
    assertEquals(
      commitMessage(workDir, "HEAD~1"),
      "orca: stack settings (discovered)"
    )
    // The header commit (HEAD) carries only the progress log its message names —
    // NOT the settings file.
    assertEquals(commitMessage(workDir, "HEAD"), "orca: progress log")
    assert(
      !commitFiles(workDir, "HEAD").contains(".orca/settings.properties"),
      s"the header commit must NOT include the settings file, got: ${commitFiles(workDir, "HEAD")}"
    )
    // The bracketing discovery events reached the event surface.
    assert(
      steps.contains(
        "no .orca/settings.properties — running stack discovery"
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
      format = DiscoveredTask(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredTask(commands =
        List(DiscoveredCommand("definitely-not-a-cmd-xyz check", "seed.txt"))
      ),
      test = DiscoveredTask(commands =
        List(DiscoveredCommand("echo test", "seed.txt"))
      )
    )
    val (setup, steps) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), "discover-demote")
    val content = os.read(OrcaDir.settingsPath(workDir))
    assert(
      content.contains(
        "# lint = definitely-not-a-cmd-xyz check   " +
          "(definitely-not-a-cmd-xyz: not found on PATH)"
      ),
      s"the demoted command must be a commented-out line with its reason: $content"
    )
    // Parsing the written file yields only the surviving commands…
    assertEquals(
      orca.settings.SettingsFile.parse(content),
      Right(StackSettings(format = List("echo fmt"), test = List("echo test")))
    )
    // …which are also what the run got.
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
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    // The delete-to-rediscover fixture: feature branch, committed header — and
    // NO settings file when the run resumes.
    val _ = git.createBranch("feat/discover-resume")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/discover-resume",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val headBefore =
      os.proc("git", "rev-parse", "HEAD").call(cwd = workDir).out.text().trim
    val canned = StackDiscoveryResult(
      format = DiscoveredTask(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredTask(),
      test = DiscoveredTask()
    )
    val (setup, _) =
      setupDiscovering(workDir, CannedDiscoveryAgent(canned), prompt)
    assertEquals(setup.stackSettings, StackSettings(format = List("echo fmt")))
    // The resume arm gives the rediscovered file its own dedicated commit
    // (the branch already existed): HEAD advances by exactly that commit,
    // which carries EXACTLY the settings file under the pinned message — the
    // file is no longer left untracked.
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
      format = DiscoveredTask(commands =
        List(DiscoveredCommand("echo fmt", "seed.txt"))
      ),
      lint = DiscoveredTask(),
      test = DiscoveredTask()
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
    val store = ProgressStore.default(workDir, prompt)
    var stageRan = false
    val throwing = new CannedDiscoveryAgent(() =>
      throw new RuntimeException("discovery boom")
    )
    val thrown = intercept[SurfacedFlowFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs(prompt),
          agent = _ => throwing,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = Some(store)
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
    "discovery: an all-unset result writes an all-commented file, warns per gate, and yields empty settings"
  ):
    val workDir = GitRepo.seeded()
    val canned = StackDiscoveryResult(
      format = DiscoveredTask(unsetReason = Some("no formatter config found")),
      lint = DiscoveredTask(),
      test = DiscoveredTask(unsetReason = Some("no test directory found"))
    )
    val (setup, steps) = setupDiscovering(
      workDir,
      CannedDiscoveryAgent(canned),
      "discover-all-unset"
    )
    assertEquals(setup.stackSettings, StackSettings.empty)
    assertEquals(
      os.read(OrcaDir.settingsPath(workDir)),
      """# orca stack settings — edit freely, commit with the project.
        |# Delete this file to re-run auto-discovery.
        |# format =   (no formatter config found)
        |# lint =   (no evidence found)
        |# test =   (no test directory found)
        |""".stripMargin
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

  test(
    "rehydrateSessions replays a codex-tagged record into the codex agent, not the lead"
  ):
    val store = storeWith(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "c-1",
        seed = "s",
        resumeWireId = Some("srv-9"),
        backend = Some("Codex")
      )
    )
    val lead = new RecordingClaude
    val codex = new RecordingCodex
    val ctx = new StubFlowContext(codexOverride = codex)
    FlowLifecycle.rehydrateSessions(ctx, lead, store)
    assertEquals(lead.recordedWire("c-1"), None)
    assertEquals(codex.recordedWire("c-1"), Some("srv-9"))

  test(
    "rehydrateSessions falls back to the lead for an untagged (older) record"
  ):
    val store = storeWith(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "old-1",
        seed = "s",
        resumeWireId = Some("srv-1")
      )
    )
    val lead = new RecordingClaude
    val ctx = new StubFlowContext()
    FlowLifecycle.rehydrateSessions(ctx, lead, store)
    assertEquals(lead.recordedWire("old-1"), Some("srv-1"))

  test(
    "rehydrateSessions skips a record with an unknown backend tag, and warns loudly (6B.1)"
  ):
    val store = storeWith(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "x-1",
        seed = "s",
        resumeWireId = Some("srv-2"),
        backend = Some("Bogus")
      )
    )
    val lead = new RecordingClaude
    val codex = new RecordingCodex
    val listener = new RecordingListener
    val ctx = new StubFlowContext(
      codexOverride = codex,
      emitTo = listener.onEvent
    )
    FlowLifecycle.rehydrateSessions(ctx, lead, store)
    assert(
      lead.recordedWire("x-1").isEmpty && codex.recordedWire("x-1").isEmpty
    )
    // Pre-6B.1 this skip was a silent for-comprehension drop; it must now
    // reach the event surface as a Step, not just vanish.
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(s =>
        s.message.contains("warning") && s.message.contains("Bogus")
      ),
      s"expected a warning naming the unknown tag; got: $steps"
    )

  test(
    "rehydrateSessions skips a record with a corrupted (unsafe) id or wire id, and warns loudly (6B.3)"
  ):
    // A hand-edited/corrupted log: the recorded id or wire id fails
    // SessionId.isSafe. rehydrateSessions must not rehydrate it raw — parse
    // it, and on failure skip with a warning, mirroring `session(...)`'s
    // reuse-arm treatment of the same corruption.
    val badIdStore = storeWith(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "../../etc/passwd",
        seed = "s",
        resumeWireId = Some("srv-3")
      )
    )
    val lead = new RecordingClaude
    val listener = new RecordingListener
    val ctx = new StubFlowContext(emitTo = listener.onEvent)
    FlowLifecycle.rehydrateSessions(ctx, lead, badIdStore)
    assert(
      lead.recordedWire("../../etc/passwd").isEmpty,
      "an unsafe recorded id must not rehydrate"
    )
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(s =>
        s.message.contains("warning") && s.message.contains("invalid")
      ),
      s"expected an invalid-id warning; got: $steps"
    )

  test(
    "rehydrateSessions skips a record with a corrupted (unsafe) wire id, and warns loudly (6B.3)"
  ):
    val badWireStore = storeWith(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "c-2",
        seed = "s",
        resumeWireId = Some(".*")
      )
    )
    val lead2 = new RecordingClaude
    val listener2 = new RecordingListener
    val ctx2 = new StubFlowContext(emitTo = listener2.onEvent)
    FlowLifecycle.rehydrateSessions(ctx2, lead2, badWireStore)
    assert(
      lead2.recordedWire("c-2").isEmpty,
      "an unsafe recorded wire id must not rehydrate"
    )
    val steps2 = listener2.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps2.exists(s =>
        s.message.contains("warning") && s.message.contains("invalid")
      ),
      s"expected an invalid-id warning; got: $steps2"
    )

  /** A fresh progress store (temp dir, header already written) carrying
    * `sessions` as its session records — the minimal fixture
    * `rehydrateSessions` reads from.
    */
  private def storeWith(sessions: SessionRecord*): ProgressStore =
    val dir = TempDirs.dir()
    val store = ProgressStore.default(dir, "rehydrate-targeted")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/rehydrate-targeted",
        promptHash = ProgressStore.hashPrompt("rehydrate-targeted")
      )
    )
    sessions.foreach(store.upsertSession)
    store

  test(
    "rehydrate: persisted client→server map is replayed into the leading model before the body"
  ):
    // An aborted run left a session record carrying a learned resumeWireId. On
    // resume, flow setup must replay it into the leading model's registry via
    // registerResumeWireId BEFORE the body runs.
    val workDir = GitRepo.seeded()
    val prompt = "rehydrate-feature"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)

    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch("feat/rehydrate-feature")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/rehydrate-feature",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.upsertSession(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "client-uuid",
        seed = "brief",
        resumeWireId = Some("ses_server_1")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: session record")

    val recorder = new RecordingClaude
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        agent = _ => recorder,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        returnToStartBranch = false,
        progressStore = Some(store),
        wiring = FlowWiring(claude = Some(_ => recorder))
      ):
        // The body observes the already-rehydrated mapping.
        assertEquals(
          recorder.recordedWire("client-uuid"),
          Some("ses_server_1"),
          "registerResumeWireId must replay the persisted mapping"
        )

  /** Drive `runFlow` directly (exit-free) with a null-sink interaction so no
    * TTY is needed and a body failure surfaces as a thrown exception rather
    * than a `System.exit`.
    */
  private def runFlowForTest(
      workDir: os.Path,
      prompt: String,
      store: ProgressStore,
      extraListeners: List[OrcaListener] = Nil
  )(body: orca.FlowControl ?=> Unit): Unit =
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = extraListeners,
        branchNaming = None,
        returnToStartBranch = false,
        progressStore = Some(store)
      )(body)

  test(
    "teardownSuccess is best-effort: a non-missing-file removal error does not fail an otherwise-successful run"
  ):
    // teardownSuccess's doc comment promises log-removal/commit/handoff errors
    // are "cosmetic — swallowed", but the removal leg only ever caught
    // NoSuchFileException. Replace the progress-log file with a non-empty
    // directory (after the last stage's own commit has already landed it as a
    // plain file) so `os.remove` throws DirectoryNotEmptyException instead —
    // a real, not-NoSuchFile IO error. The run must still complete: nothing
    // should escape teardown and turn a successful run into exit 1.
    val workDir = GitRepo.seeded()
    val prompt = "bestEffort-teardown"
    val store = ProgressStore.default(workDir, prompt)
    runFlowForTest(workDir, prompt, store):
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
    "runFlow closes the context (and its agents) even when the body throws"
  ):
    // ctx.close() runs in runFlow's `finally`, so it must fire on the failure
    // path too — not just on success. Wire a recording opencode agent and
    // assert its close() ran after a body that throws.
    val workDir = GitRepo.seeded()
    val prompt = "close-on-body-throw"
    var opencodeClosed = false
    val recorder = new RecordingOpencode(() => opencodeClosed = true)
    val thrown = intercept[SurfacedFlowFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs(prompt),
          stackSettings = Some(StackSettings.empty),
          agent = _ => StubAgent.claude,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = None,
          wiring = FlowWiring(opencode = Some(_ => recorder))
        ):
          throw new RuntimeException("boom in body")
    assertEquals(thrown.cause.getMessage, "boom in body")
    assert(
      opencodeClosed,
      "ctx.close() must run on the failure path too, closing the opencode agent"
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
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction)
      ):
        // body does nothing — no code changes
        summon[orca.FlowContext].emit(OrcaEvent.Step("no-op"))
    // Back on main.
    assertEquals(git.currentBranch(), "main")
    // The feature branch must be gone (auto-deleted as throwaway).
    // Verify by checking git branch list: no branch other than main exists.
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
        agent = _ => StubAgent.claude,
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

  test(
    "success teardown with returnToStartBranch=true returns to start, keeps branch"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "code-flow-return"
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
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction),
        returnToStartBranch = true
      ):
        featureBranchName = summon[orca.FlowContext].git.currentBranch()
        val _ = stage("write code"):
          os.write(workDir / "code.txt", "real code")
          "done"
    // PR-flow behaviour: HEAD returns to the starting branch…
    assertEquals(git.currentBranch(), "main")
    // …but the feature branch is kept (it holds the work / backs the PR).
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

  test("R5: failure teardown keeps feature branch regardless of code changes"):
    // A flow that crashes must NOT delete the branch — it needs to stay for resume.
    val workDir = GitRepo.seeded()
    val prompt = "failure-keeps-branch"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    var featureBranchName = ""
    val _ = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store):
        // Capture the feature branch name before the crash.
        featureBranchName = summon[orca.FlowControl].git.currentBranch()
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom")
    // On the feature branch, not main.
    assertNotEquals(git.currentBranch(), "main")
    assert(featureBranchName.nonEmpty, "must have captured feature branch name")
    // Feature branch still exists (not deleted).
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
    // When `branchNaming = None` (the default), `flowSetup` uses
    // `BranchNamingStrategy.shortenPrompt`. With `StubAgent.claude`, `cheap`
    // returns `this` (haiku = this) and `autonomous` throws
    // `UnsupportedOperationException`; `shortenPrompt` catches the failure and
    // falls back to `slug(userPrompt)`. This pins that the default is
    // `shortenPrompt`, not the old `fromText`.
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
        agent = _ => StubAgent.claude,
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
    // The headline hazard this task closes: a `branchNaming` strategy (or,
    // in production, the cheap-model reply `shortenPrompt` slugs) that
    // resolves to "main" must NOT bind the whole flow to the repo's default
    // branch. Today (pre-fix) `freshRun` calls `checkoutOrCreate("main")`
    // with zero protected-branch check, so this test is RED before
    // `FeatureBranch` lands: `featureBranchName` observes "main" and no
    // protecting Step event is emitted.
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
        agent = _ => StubAgent.claude,
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
    // The other tracker hazard task 3.4 closes: `checkoutOrCreate`'s
    // silent-adopt path took over ANY existing branch with the resolved
    // name, binding the whole run to whatever unrelated history that branch
    // already carried — with zero signal. Pre-create a branch with the exact
    // name `freshRun` will resolve to (a deterministic `branchNaming`), give
    // it a commit of its own ("unrelated history"), then run a fresh flow
    // that resolves to that same name. Today (pre-fix) `checkoutOrCreate`
    // just checks it out and proceeds silently — this test is RED before the
    // create/checkout split lands: `featureBranchName` observes
    // `"taken-name"` and the pre-existing branch's head has moved.
    val workDir = GitRepo.seeded()
    val prompt = "adoption-hazard"
    val git = new OsGitTool(workDir)
    val listener = new RecordingListener
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch("taken-name")
    os.write(workDir / "unrelated.txt", "pre-existing work")
    val _ = git.commit("unrelated pre-existing commit")
    val preExistingHead = os
      .proc("git", "rev-parse", "taken-name")
      .call(cwd = workDir)
      .out
      .text()
      .trim
    val _ = git.checkout("main")
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
        agent = _ => StubAgent.claude,
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
    // The interaction the brief calls out: if the resolved name already went
    // to the deterministic `flow-<hash>` fallback (here: because a
    // protected-name collision fires first), a git-level "already exists"
    // collision on THAT fallback must abort rather than hash a second time —
    // a deterministic name colliding twice for the same prompt means a
    // previous run's branch is genuinely still there, and the user must
    // decide, not have orca guess again. Pre-create the exact deterministic
    // fallback name as an unrelated branch, then force the protected-name
    // fallback to land on it by resolving to "main".
    val workDir = GitRepo.seeded()
    val prompt = "fallback-collision-hazard"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    val expectedFallback = BranchNamingStrategy.flowFallbackName(prompt)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch(expectedFallback)
    val _ = git.checkout("main")
    val listener = new RecordingListener
    val thrown = intercept[SurfacedFlowFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs(prompt),
          stackSettings = Some(StackSettings.empty),
          agent = _ => StubAgent.claude,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = List(listener),
          branchNaming = Some(BranchNamingStrategy.fromText("main")),
          returnToStartBranch = false,
          progressStore = Some(store)
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

  test(
    "surfaced: a setup resume-refusal reaches the user as one Error and escapes as SurfacedFlowFailure"
  ):
    // The silent-exit family's headline case: a tampered header makes `setup`
    // throw the resume refusal. Before the `surfaced` bracket, that escaped
    // `flow()` unreported — banner + exit 1 and nothing else. Now it must reach
    // the user's event surface exactly once, and escape marked as surfaced.
    val workDir = GitRepo.seeded()
    val prompt = "surfaced-tampered"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch("feat/surfaced-tampered")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/surfaced-tampered",
        promptHash = "deadbeefcafe"
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val listener = new RecordingListener
    val thrown = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store, extraListeners = List(listener)):
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
    // Folded from the deleted R32 (same fixture, same validation branch): the
    // header-validation failure reason rides along in the same message.
    assert(
      thrown.cause.getMessage.contains("failed validation"),
      s"abort message must mention validation failure: ${thrown.cause.getMessage}"
    )

  test(
    "surfaced: a resume header naming a protected branch is refused end-to-end through setup"
  ):
    // Unlike the tampered-promptHash fixture above, this one names a
    // genuinely protected branch (`master`) as the header's feature branch —
    // exercising the OTHER `validateHeader` failure mode end-to-end through
    // `FlowLifecycle.setup`, not just at the `RecoveryCheckTest` unit level.
    val workDir = GitRepo.seeded()
    val prompt = "resume-protected-branch"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch("feat/resume-protected-branch")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "master",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    val listener = new RecordingListener
    val thrown = intercept[SurfacedFlowFailure]:
      runFlowForTest(workDir, prompt, store, extraListeners = List(listener)):
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
    "surfaced: a rehydration failure reaches the user as one Error, not a silent exit"
  ):
    // rehydrateSessions runs OUTSIDE the body's try today; a throw there
    // escaped unreported exactly like a setup failure. Force one: resume in
    // place (valid header) with a persisted resume-wire-id, and a lead agent
    // whose registry throws when the runtime replays it.
    val workDir = GitRepo.seeded()
    val prompt = "surfaced-rehydrate"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val _ = git.createBranch("feat/surfaced-rehydrate")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/surfaced-rehydrate",
        promptHash = ProgressStore.hashPrompt(prompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    store.upsertSession(
      SessionRecord(
        name = "s",
        occurrence = 0,
        id = "client-uuid",
        seed = "brief",
        resumeWireId = Some("ses_server_1")
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: session record")

    val thrower = new ThrowingRehydrateClaude
    val listener = new RecordingListener
    val thrown = intercept[SurfacedFlowFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs(prompt),
          stackSettings = Some(StackSettings.empty),
          agent = _ => thrower,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = List(listener),
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = Some(store),
          wiring = FlowWiring(claude = Some(_ => thrower))
        ):
          val _ = stage("never-runs")("x")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assertEquals(thrown.cause.getMessage, "rehydrate boom")

  test(
    "surfaced: a body failure whose teardownFailure ALSO throws surfaces once; the reset failure rides along suppressed"
  ):
    // The body reports its Error at the stage boundary (one Error, no
    // double-report). failure teardown (`resetHard`) still runs — and if the
    // reset itself throws, that must NOT mask the original body failure: it is
    // attached as suppressed so the user sees the body message and debug sees
    // both.
    val workDir = GitRepo.seeded()
    val prompt = "surfaced-suppressed"
    val store = ProgressStore.default(workDir, prompt)
    val listener = new RecordingListener
    val thrown = intercept[SurfacedFlowFailure]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs(prompt),
          stackSettings = Some(StackSettings.empty),
          agent = _ => StubAgent.claude,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = List(listener),
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = Some(store),
          wiring = FlowWiring(git = Some(new ResetThrowingGit(workDir)))
        ):
          val _ = stage[String]("crash"):
            throw new RuntimeException("boom body")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assertEquals(thrown.cause.getMessage, "boom body")
    assert(
      thrown.cause.getSuppressed.exists(_.getMessage.contains("reset boom")),
      s"the failing reset must be suppressed on the original: " +
        thrown.cause.getSuppressed.mkString(", ")
    )
    // The reset failure ALSO gets a user-visible note (in addition to, not
    // instead of, the suppressed exception above).
    val steps = listener.events.collect { case s: OrcaEvent.Step => s }
    assert(
      steps.exists(_.message.contains("workspace reset failed")),
      s"a Step warning about the failed reset must be emitted: $steps"
    )

  // --- flow() reentrancy/concurrency guards --------------------------------

  test(
    "reentrancy guards: a nested runFlow in the same process is refused before any git mutation, and the outer flow is unaffected"
  ):
    val workDir = GitRepo.seeded()
    val prompt = "nested-guard"
    var innerThrown: Option[Throwable] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs(prompt),
        stackSettings = Some(StackSettings.empty),
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        returnToStartBranch = false,
        progressStore = None
      ):
        innerThrown =
          try
            runFlow(
              args = OrcaArgs("inner"),
              stackSettings = Some(StackSettings.empty),
              agent = _ => StubAgent.claude,
              workDir = workDir,
              interaction = Some(interaction),
              extraListeners = Nil,
              branchNaming = None,
              returnToStartBranch = false,
              progressStore = None
            )(())
            None
          catch case e: Throwable => Some(e)
    val thrown = innerThrown.getOrElse(fail("nested runFlow must throw"))
    assert(thrown.isInstanceOf[orca.OrcaFlowException])
    assertEquals(thrown.getMessage, "a flow is already running in this process")
    assert(
      !thrown.isInstanceOf[SurfacedFlowFailure],
      "a pre-ctx guard failure must NOT be wrapped in SurfacedFlowFailure"
    )
    // The outer flow, unaffected by the refused nested attempt, still ends
    // cleanly back on the starting branch — the guard must not corrupt an
    // outer flow's state (ADR 0018 §6).
    val branch =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(branch, "main")

  test(
    "reentrancy guards: a workdir lock held by a live PID refuses a new runFlow"
  ):
    val workDir = GitRepo.seeded()
    val livePid = ProcessHandle.current().pid()
    os.write(
      workDir / ".orca" / "cache" / "flow.lock",
      livePid.toString,
      createFolders = true
    )
    val thrown = intercept[orca.OrcaFlowException]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs("live-pid"),
          stackSettings = Some(StackSettings.empty),
          agent = _ => StubAgent.claude,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = None
        ):
          ()
    // `intercept[orca.OrcaFlowException]` above already pins the static type
    // — an unwrapped `OrcaFlowException`, not a `SurfacedFlowFailure` — since
    // the two types are unrelated; nothing further to assert on that front.
    assertEquals(
      thrown.getMessage,
      s"a flow is already running in this working tree (pid $livePid)"
    )
    // The refusal must not steal or clear a lock still held by a live PID.
    assertEquals(
      os.read(workDir / ".orca" / "cache" / "flow.lock").trim,
      livePid.toString
    )

  test(
    "reentrancy guards: a stale dead-PID lock is stolen with a warning, and the flow proceeds"
  ):
    val workDir = GitRepo.seeded()
    val dead = os.proc("true").spawn()
    dead.join(): Unit
    val deadPid = dead.wrapped.pid()
    os.write(
      workDir / ".orca" / "cache" / "flow.lock",
      deadPid.toString,
      createFolders = true
    )
    val originalErr = System.err
    val captured = new ByteArrayOutputStream()
    System.setErr(new PrintStream(captured))
    try
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs("steal"),
          stackSettings = Some(StackSettings.empty),
          agent = _ => StubAgent.claude,
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None,
          returnToStartBranch = false,
          progressStore = None
        ):
          summon[FlowContext].emit(OrcaEvent.Step("ran"))
    finally System.setErr(originalErr)
    val warning = captured.toString
    assert(
      warning.contains("stale lock") && warning.contains(deadPid.toString),
      s"expected a stale-lock warning mentioning pid $deadPid, got: $warning"
    )
    // The guard released cleanly after a successful run — no lock left behind.
    assert(
      !os.exists(workDir / ".orca" / "cache" / "flow.lock"),
      "lock must be released after a successful run"
    )

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
        args = OrcaArgs("lock-not-committed"),
        stackSettings = Some(StackSettings.empty),
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        returnToStartBranch = false,
        progressStore = None
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

  test("acquireWorkdir places the lock under .orca/cache"):
    val workDir = GitRepo.seeded()
    val lockPath = FlowLock.acquireWorkdir(workDir)
    try
      assertEquals(lockPath, workDir / ".orca" / "cache" / "flow.lock")
      assert(os.exists(lockPath))
    finally FlowLock.releaseWorkdir(lockPath)

  /** Records every `OrcaEvent` it sees, so the boundary-emission tests can
    * count how many `OrcaEvent.Error`s a failing run produced.
    */
  private class RecordingListener extends OrcaListener:
    private val seen = new AtomicReference[List[OrcaEvent]](Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  /** A `ClaudeAgent` over a real durable capability, so a test can assert the
    * lifecycle rehydrated the persisted resume-wire-id map into the RIGHT agent
    * — query the registered mapping via [[recordedWire]]. All LLM methods
    * throw; the rehydration tests never invoke the model.
    */
  private class RecordingClaude extends ClaudeAgent:
    private val support = SessionSupport
      .durable[BackendTag.ClaudeCode.type](IdScheme.ServerMinted, _ => false)

    /** The wire id rehydration registered for `client`, if any. */
    def recordedWire(client: String): Option[String] =
      support
        .persistableWireId(SessionId[BackendTag.ClaudeCode.type](client))
        .map(WireSessionId.value(_))

    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
      Some(support)

    val name = "recording-claude"
    def haiku = this
    def sonnet = this
    def opus = this
    def fable = this
    def withModel(model: Model) = this
    def withNetworkTools(t: Seq[String]) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      throw new UnsupportedOperationException

  /** A `ClaudeAgent` whose session-capability accessor throws when the runtime
    * touches it during rehydration — so a rehydration-phase failure can be
    * exercised end-to-end. Every LLM call throws (no test reaches one).
    */
  private class ThrowingRehydrateClaude extends ClaudeAgent:
    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
      throw new RuntimeException("rehydrate boom")

    val name = "throwing-rehydrate-claude"
    def haiku = this
    def sonnet = this
    def opus = this
    def fable = this
    def withModel(model: Model) = this
    def withNetworkTools(t: Seq[String]) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      throw new UnsupportedOperationException

  /** An `OsGitTool` whose `resetHard` always throws — to exercise the
    * body-phase failure teardown throwing while it handles a body failure, so
    * the reset error is attached as suppressed rather than masking the
    * original.
    */
  private class ResetThrowingGit(workDir: os.Path) extends OsGitTool(workDir):
    override def resetHard()(using WorkspaceWrite): Unit =
      throw new RuntimeException("reset boom")

  /** Codex counterpart of [[RecordingClaude]], used to assert that a
    * codex-tagged session record rehydrates into the codex agent rather than
    * the (claude) lead.
    */
  private class RecordingCodex extends CodexAgent:
    private val support = SessionSupport
      .durable[BackendTag.Codex.type](IdScheme.ServerMinted, _ => false)

    /** The wire id rehydration registered for `client`, if any. */
    def recordedWire(client: String): Option[String] =
      support
        .persistableWireId(SessionId[BackendTag.Codex.type](client))
        .map(WireSessionId.value(_))

    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.Codex.type]] =
      Some(support)

    val name = "recording-codex"
    def mini = this
    def withModel(model: Model) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.Codex.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
      throw new UnsupportedOperationException

  /** An `OpencodeAgent` whose `close()` calls `onClose` — used to pin that
    * `runFlow` closes the context (and its agents) on the body-throw path, not
    * just on success. Every LLM call throws — no test reaches one.
    */
  private class RecordingOpencode(onClose: () => Unit) extends OpencodeAgent:
    val name = "recording-opencode"
    def anthropicOpus = this
    def anthropicSonnet = this
    def anthropicHaiku = this
    def openaiSol = this
    def openaiTerra = this
    def openaiLuna = this
    def withModel(providerModel: String) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Opencode.type, O] =
      throw new UnsupportedOperationException
    override private[orca] def close(): Unit = onClose()

  /** Throws — for `FlowContext` accessors a test doesn't wire and expects
    * `rehydrateSessions` never to touch (it resolves purely off the per-backend
    * accessors matching a record's `backend` tag).
    */
  private def notWired(name: String): Nothing =
    throw new NotImplementedError(s"$name is not wired in StubFlowContext")

  /** Minimal `FlowContext` stub for the targeted-rehydration tests above: only
    * the per-backend accessor(s) a test overrides are live; every other member
    * (including `claude`, when the test doesn't pass one) throws if touched.
    */
  private class StubFlowContext(
      claudeOverride: => ClaudeAgent = notWired("claude"),
      codexOverride: => CodexAgent = notWired("codex"),
      opencodeOverride: => OpencodeAgent = notWired("opencode"),
      piOverride: => PiAgent = notWired("pi"),
      geminiOverride: => GeminiAgent = notWired("gemini"),
      /** Records every emitted event — `Nil` (the default) keeps `emit` a true
        * no-op for tests that don't care; the two rehydration-warning tests
        * below pass a listener to assert on the emitted `Step`.
        */
      emitTo: OrcaEvent => Unit = _ => ()
  ) extends FlowContext:
    type LeadB = BackendTag.ClaudeCode.type
    def agent: Agent[LeadB] = notWired("agent")
    def claude: ClaudeAgent = claudeOverride
    def codex: CodexAgent = codexOverride
    def opencode: OpencodeAgent = opencodeOverride
    def pi: PiAgent = piOverride
    def gemini: GeminiAgent = geminiOverride
    def git: GitTool = notWired("git")
    def gh: GitHubTool = notWired("gh")
    def fs: FsTool = notWired("fs")
    def workDir: os.Path = notWired("workDir")
    def stackSettings: orca.StackSettings = notWired("stackSettings")
    def userPrompt: String = ""
    def emit(event: OrcaEvent): Unit = emitTo(event)
    // Rehydration tests never fail through this stub; a no-op reported-set is fine.
    private[orca] def markErrorReported(e: Throwable): Unit = ()
    private[orca] def errorAlreadyReported(e: Throwable): Boolean = false

end FlowLifecycleTest
