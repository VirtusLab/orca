package orca.runner

import orca.{FlowContext, InStage, OrcaArgs, runFlow, stage, flow}
import orca.events.OrcaEvent
import orca.llm.DefaultPrompts
import orca.progress.{ProgressHeader, ProgressStore, StageEntry}
import orca.runner.terminal.TerminalInteraction
import orca.tools.OsGitTool
import orca.tools.opencode.OpencodeLauncher
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicInteger

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
        llm = StubLlm.claude,
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
        llm = StubLlm.claude,
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
    // Success teardown returns to the branch that was current when flow() was
    // called (feat/lifecycle-resume, since we were already on it).
    val branch =
      os.proc("git", "rev-parse", "--abbrev-ref", "HEAD")
        .call(cwd = workDir)
        .out
        .text()
        .trim
    assertEquals(
      branch,
      "feat/lifecycle-resume",
      "flow must return to the branch that was current at call time"
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
    "runFlow resumes after a crash: stage one replays once and ends on the start branch"
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
    //
    // NOTE (deferred, recovery hardening / R30,R32): the progress log is tracked
    // ON the feature branch, so it is not visible from another branch. Resuming
    // from an arbitrary branch (a fresh process sitting on `main`), and returning
    // a resumed run to the *original* start branch via `header.startingBranch`
    // rather than the re-run's current branch, are E-polish items — not covered
    // here. This test pins the supported in-place path.
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
      featureBranch,
      "a successful in-place resumed run returns to where it started"
    )

  /** Drive `runFlow` directly (exit-free) with a null-sink interaction so no
    * TTY is needed and a body failure surfaces as a thrown exception rather
    * than a `System.exit`.
    */
  private def runFlowForTest(
      workDir: os.Path,
      prompt: String,
      store: ProgressStore
  )(body: orca.FlowControl ?=> Unit): Unit =
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs(prompt),
        llm = StubLlm.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        progressStore = Some(store),
        claude = None,
        opencode = None,
        opencodeLauncher = OpencodeLauncher.default,
        pi = None,
        git = None,
        gh = None,
        fs = None,
        prompts = DefaultPrompts
      )(body)

end FlowLifecycleTest
