package orca.shell.resume

import orca.WorkspaceWrite
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.testkit.TempDirs

class ResumeDetectorTest extends munit.FunSuite:

  // All progress-log writes require a WorkspaceWrite token; mint one for the
  // suite, mirroring ProgressStoreTest.
  given WorkspaceWrite = WorkspaceWrite.unsafe

  private def header(
      userPrompt: Option[String] = Some("fix the flaky test"),
      flowName: Option[String] = Some("implement.sc")
  ): ProgressHeader =
    ProgressHeader(
      startingBranch = "main",
      branch = "feat/resume",
      promptHash = "abc123def456",
      branchMode = BranchMode.Created,
      userPrompt = userPrompt,
      flowName = flowName
    )

  // The scan's own guards (symlinked log file, symlinked or missing `.orca`)
  // belong to ProgressScanTest, which owns that logic.
  test("detect is None when the scan finds no progress log"):
    val workDir = TempDirs.dir()
    os.makeDir.all(workDir / ".orca")
    assertEquals(ResumeDetector.detect(workDir), None)

  test("detect finds a fresh log's recorded flow name and task text"):
    val workDir = TempDirs.dir()
    ProgressStore.default(workDir, "fix the flaky test").writeHeader(header())
    assertEquals(
      ResumeDetector.detect(workDir),
      Some(InterruptedRun("implement.sc", "fix the flaky test"))
    )

  test("detect is None for a log missing flowName (a run outside the shell)"):
    val workDir = TempDirs.dir()
    ProgressStore
      .default(workDir, "fix the flaky test")
      .writeHeader(header(flowName = None))
    assertEquals(ResumeDetector.detect(workDir), None)

  test(
    "detect drops a flowName that isn't a bare .sc filename (forged header)"
  ):
    List("../../evil.sc", "/abs/evil.sc", "sub/x.sc", "-flag.sc", "x.txt")
      .foreach: forged =>
        val workDir = TempDirs.dir()
        ProgressStore
          .default(workDir, "fix the flaky test")
          .writeHeader(header(flowName = Some(forged)))
        assertEquals(ResumeDetector.detect(workDir), None, forged)

  test("detect is None for an old-format log missing userPrompt"):
    val workDir = TempDirs.dir()
    ProgressStore
      .default(workDir, "fix the flaky test")
      .writeHeader(header(userPrompt = None))
    assertEquals(ResumeDetector.detect(workDir), None)

  test("detect is None for a corrupt (unparseable) log, silently"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, "fix the flaky test")
    store.writeHeader(header())
    os.write.over(store.path, "not json {{{")
    assertEquals(ResumeDetector.detect(workDir), None)

  test("detect picks the newest of multiple unfinished logs by mtime"):
    val workDir = TempDirs.dir()
    val older = ProgressStore.default(workDir, "older prompt")
    older.writeHeader(
      header(userPrompt = Some("older prompt"), flowName = Some("a.sc"))
    )
    val newer = ProgressStore.default(workDir, "newer prompt")
    newer.writeHeader(
      header(userPrompt = Some("newer prompt"), flowName = Some("b.sc"))
    )
    // Force a distinguishable mtime order regardless of write-speed timing.
    val _ = os.mtime.set(older.path, System.currentTimeMillis() - 60000)
    assertEquals(
      ResumeDetector.detect(workDir),
      Some(InterruptedRun("b.sc", "newer prompt"))
    )
