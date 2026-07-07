package orca.runner

import orca.{
  BranchNamingStrategy,
  FlowContext,
  InStage,
  OrcaArgs,
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
  ToolSet,
  onWire
}
import orca.backend.{Dispatch, SessionRegistry, SessionSupport}
import orca.progress.{ProgressHeader, ProgressStore, SessionRecord, StageEntry}
import orca.runner.terminal.TerminalInteraction
import orca.tools.{FsTool, GitHubTool, GitTool, OsGitTool}
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** Tests for the flow lifecycle: success teardown, failure teardown, and resume
  * across two calls. Each test uses a real temp git repo via `TempRepo` and a
  * null-sink `TerminalInteraction` so no TTY is required.
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
    val workDir = TempRepo.create()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs("lifecycle-success"),
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
    val workDir = TempRepo.create()
    val git = new OsGitTool(workDir)
    val prompt = "lifecycle-failure"
    val store = ProgressStore.default(workDir, prompt)

    given InStage = InStage.unsafe

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
    store.appendEntry(StageEntry("stage-one#0", "stage-one", "\"done\""))
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
    val workDir = TempRepo.create()
    val prompt = "lifecycle-resume"
    val store = ProgressStore.default(workDir, prompt)
    val invocations = new AtomicInteger(0)

    given InStage = InStage.unsafe

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
      StageEntry("resumable-stage#0", "resumable-stage", "\"ok\"")
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
    val workDir = TempRepo.create()
    val prompt = "crash-feature"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()

    val thrown = intercept[RuntimeException]:
      runFlowForTest(workDir, prompt, store):
        val _ = stage("stage-one"):
          os.write(workDir / "one.txt", "content")
          "one-done"
        val _ = stage[String]("stage-two"):
          throw new RuntimeException("boom in stage two")
    assertEquals(thrown.getMessage, "boom in stage two")

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
    val workDir = TempRepo.create()
    val prompt = "resume-feature"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()
    val stageOneRuns = new AtomicInteger(0)

    // First run: crashes in stage two.
    val _ = intercept[RuntimeException]:
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
    val workDir = TempRepo.create()
    val prompt = "boundary-stage-once"
    val store = ProgressStore.default(workDir, prompt)
    val listener = new RecordingListener
    val _ = intercept[RuntimeException]:
      runFlowForTest(workDir, prompt, store, extraListeners = List(listener)):
        val _ = stage[String]("crash"):
          throw new RuntimeException("boom")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")

  test("runFlow reports a body failure outside any stage exactly once"):
    // A body that throws directly (never entering a stage) is reported once at
    // the flow boundary itself.
    val workDir = TempRepo.create()
    val prompt = "boundary-body-once"
    val store = ProgressStore.default(workDir, prompt)
    val listener = new RecordingListener
    val _ = intercept[RuntimeException]:
      runFlowForTest(workDir, prompt, store, extraListeners = List(listener)):
        throw new RuntimeException("boom outside any stage")
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")

  test(
    "R20: snapshot-before-stash restores the log file if the stash removed it"
  ):
    // The end-to-end stash hazard is belt-and-suspenders (the log is normally
    // committed, so the stash can't remove it), so cover the helper directly:
    // a snapshot taken while the file exists restores its exact bytes after the
    // file is gone, and is a no-op when the file still exists.
    val dir = os.temp.dir()
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
    val workDir = TempRepo.create()
    val prompt = "merged-hazard"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)

    given InStage = InStage.unsafe
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

    val thrown = intercept[orca.OrcaFlowException]:
      runFlowForTest(workDir, prompt, store):
        val _ = stage("never-runs"):
          "x"
    assert(
      thrown.getMessage.contains("feat/merged-hazard") &&
        thrown.getMessage.contains(currentBranch) &&
        thrown.getMessage.contains("merged"),
      s"abort message must name both branches and the merge hazard: ${thrown.getMessage}"
    )

  test(
    "R32: a tampered header (prompt-hash mismatch) aborts rather than resuming"
  ):
    // A committed log on the feature branch whose promptHash does not match the
    // current prompt — a hand-edited/mismatched header. Resume must hard-abort.
    val workDir = TempRepo.create()
    val prompt = "tampered-feature"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)

    given InStage = InStage.unsafe
    val _ = git.createBranch("feat/tampered-feature")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "feat/tampered-feature",
        promptHash = "deadbeefcafe"
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")

    val thrown = intercept[orca.OrcaFlowException]:
      runFlowForTest(workDir, prompt, store):
        val _ = stage("never-runs"):
          "x"
    assert(
      thrown.getMessage.contains("failed validation"),
      s"abort message must mention validation failure: ${thrown.getMessage}"
    )

  test(
    "setup: a corrupt (unparseable) progress log proceeds FRESH — new branch, header written"
  ):
    // A garbage-bytes file at the store's path (a torn/truncated write, not a
    // "no log yet" absence). `loadDetailed()` returns `Corrupt`, and `setup`
    // must take the same fresh-run path an absent log would — resolve +
    // create a branch and commit a brand-new header — rather than throwing or
    // silently doing nothing. (The WARN this path also emits, via the logger
    // and a `[orca]` stderr line, has no cheap capture point in this test
    // harness — verified by code review; `loadDetailed()`'s `Corrupt` branch
    // itself is pinned at the store level in `ProgressStoreTest`.)
    val workDir = TempRepo.create()
    val prompt = "corrupt-log-fresh"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    val startBranch = git.currentBranch()

    os.makeDir.all(store.path / os.up)
    os.write.over(store.path, "not json {{{", createFolders = true)

    val setup = FlowLifecycle.setup(
      args = OrcaArgs(prompt),
      agent = StubAgent.claude,
      git = git,
      branchNaming = None,
      store = store
    )

    // A fresh branch was resolved and created, distinct from the start branch
    // — not an abort, and not a no-op that leaves HEAD where it was.
    assertEquals(git.currentBranch(), setup.featureBranch)
    assertNotEquals(setup.featureBranch, startBranch)
    assertEquals(setup.startBranch, startBranch)
    // A brand-new header was written and committed — the corrupt bytes are
    // gone, replaced by a valid fresh log with no entries.
    val loaded = store.load()
    assert(loaded.isDefined, "a fresh header must have been written")
    assertEquals(loaded.get.header.branch, setup.featureBranch)
    assertEquals(loaded.get.entries, Nil)

  test(
    "rehydrateSessions replays a codex-tagged record into the codex agent, not the lead"
  ):
    val store = storeWith(
      SessionRecord(
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
    assertEquals(lead.registered, Nil)
    assertEquals(codex.registered, List("c-1" -> "srv-9"))

  test(
    "rehydrateSessions falls back to the lead for an untagged (older) record"
  ):
    val store = storeWith(
      SessionRecord(
        occurrence = 0,
        id = "old-1",
        seed = "s",
        resumeWireId = Some("srv-1")
      )
    )
    val lead = new RecordingClaude
    val ctx = new StubFlowContext()
    FlowLifecycle.rehydrateSessions(ctx, lead, store)
    assertEquals(lead.registered, List("old-1" -> "srv-1"))

  test("rehydrateSessions skips a record with an unknown backend tag"):
    val store = storeWith(
      SessionRecord(
        occurrence = 0,
        id = "x-1",
        seed = "s",
        resumeWireId = Some("srv-2"),
        backend = Some("Bogus")
      )
    )
    val lead = new RecordingClaude
    val codex = new RecordingCodex
    val ctx = new StubFlowContext(codexOverride = codex)
    FlowLifecycle.rehydrateSessions(ctx, lead, store)
    assert(lead.registered.isEmpty && codex.registered.isEmpty)

  /** A fresh progress store (temp dir, header already written) carrying
    * `sessions` as its session records — the minimal fixture
    * `rehydrateSessions` reads from.
    */
  private def storeWith(sessions: SessionRecord*): ProgressStore =
    val dir = os.temp.dir()
    val store = ProgressStore.default(dir, "rehydrate-targeted")
    given InStage = InStage.unsafe
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
    val workDir = TempRepo.create()
    val prompt = "rehydrate-feature"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)

    given InStage = InStage.unsafe
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
          recorder.registered,
          List(("client-uuid", "ses_server_1")),
          "registerResumeWireId must be called once with the persisted mapping"
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
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = extraListeners,
        branchNaming = None,
        returnToStartBranch = false,
        progressStore = Some(store)
      )(body)

  test(
    "runFlow closes the context (and its agents) even when the body throws"
  ):
    // ctx.close() runs in runFlow's `finally`, so it must fire on the failure
    // path too — not just on success. Wire a recording opencode agent and
    // assert its close() ran after a body that throws.
    val workDir = TempRepo.create()
    val prompt = "close-on-body-throw"
    var opencodeClosed = false
    val recorder = new RecordingOpencode(() => opencodeClosed = true)
    val thrown = intercept[RuntimeException]:
      supervised:
        val interaction = TerminalInteraction.start(
          out = new PrintStream(new ByteArrayOutputStream()),
          useColor = false,
          animated = false
        )
        runFlow(
          args = OrcaArgs(prompt),
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
    assertEquals(thrown.getMessage, "boom in body")
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
    val workDir = TempRepo.create()
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
    val workDir = TempRepo.create()
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
    val workDir = TempRepo.create()
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
    val workDir = TempRepo.create()
    val prompt = "failure-keeps-branch"
    val store = ProgressStore.default(workDir, prompt)
    val git = new OsGitTool(workDir)
    var featureBranchName = ""
    val _ = intercept[RuntimeException]:
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
    val workDir = TempRepo.create()
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

  /** Records every `OrcaEvent` it sees, so the boundary-emission tests can
    * count how many `OrcaEvent.Error`s a failing run produced.
    */
  private class RecordingListener extends OrcaListener:
    private val seen = new AtomicReference[List[OrcaEvent]](Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  /** In-memory `SessionRegistry` that records every `commitSuccess` call as a
    * `(client, server)` string pair, exposed via `registered`. Shared by the
    * per-backend recording stubs below (routed through the `final`
    * `Agent.registerResumeWireId` → [[SessionSupport.register]] →
    * `commitSuccess`) so each stub only wires its own instance rather than
    * repeating the bookkeeping.
    */
  private class RecordingRegistry[B <: BackendTag] extends SessionRegistry[B]:
    private var _registered: List[(String, String)] = Nil
    def registered: List[(String, String)] = _registered
    def dispatchFor(client: SessionId[B]): Dispatch[B] =
      Dispatch.Fresh(client.onWire)
    def commitSuccess(client: SessionId[B], server: WireSessionId[B]): Unit =
      _registered = _registered :+ (client.value -> server.value)
    def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]] = None

  /** A `ClaudeAgent` that records `registerResumeWireId` calls, to assert the
    * lifecycle rehydrates the persisted resume-wire-id map into the RIGHT
    * agent. All LLM methods throw — the rehydration tests never invoke the
    * model.
    */
  private class RecordingClaude extends ClaudeAgent:
    private val registry = new RecordingRegistry[BackendTag.ClaudeCode.type]
    def registered: List[(String, String)] = registry.registered

    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
      Some(SessionSupport.Durable(registry, _ => false))

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

  /** Codex counterpart of [[RecordingClaude]], used to assert that a
    * codex-tagged session record rehydrates into the codex agent rather than
    * the (claude) lead.
    */
  private class RecordingCodex extends CodexAgent:
    private val registry = new RecordingRegistry[BackendTag.Codex.type]
    def registered: List[(String, String)] = registry.registered

    override private[orca] def sessionSupport
        : Option[SessionSupport[BackendTag.Codex.type]] =
      Some(SessionSupport.Durable(registry, _ => false))

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
    def openaiGpt5 = this
    def openaiGpt5Codex = this
    def openaiGpt5Mini = this
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
      geminiOverride: => GeminiAgent = notWired("gemini")
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
    def userPrompt: String = ""
    def emit(event: OrcaEvent): Unit = ()
    // Rehydration tests never fail through this stub; a no-op reported-set is fine.
    private[orca] def markErrorReported(e: Throwable): Unit = ()
    private[orca] def errorAlreadyReported(e: Throwable): Boolean = false

end FlowLifecycleTest
