package orca.shell.resume

import orca.{OrcaDir, RunKey, WorkspaceWrite}
import orca.gitref.CommitHash
import orca.progress.{BranchMode, FlowSource, ProgressHeader, ProgressStore}
import orca.testkit.{TempDirs, branchName}

class ResumeDetectorTest extends munit.FunSuite:

  // All progress-log writes require a WorkspaceWrite token; mint one for the
  // suite, mirroring ProgressStoreTest.
  given WorkspaceWrite = WorkspaceWrite.unsafe

  private def header(
      userPrompt: String = "fix the flaky test",
      flow: Option[FlowSource] = Some(FlowSource.Catalog("implement.sc"))
  ): ProgressHeader =
    ProgressHeader(
      startingBranch = Some(branchName("main")),
      branch = branchName("feat/resume"),
      branchMode = BranchMode.Created,
      userPrompt = userPrompt,
      flow = flow,
      startingCommit = CommitHash.from("0" * 40).get
    )

  // The scan's own guards (symlinked log file, symlinked or missing `.orca`)
  // belong to ProgressScanTest, which owns that logic.
  test("detect is None when the scan finds no progress log"):
    val workDir = TempDirs.dir()
    os.makeDir.all(workDir / ".orca")
    assertEquals(ResumeDetector.detect(List(workDir)), None)

  test("detect finds a fresh log's recorded flow, task text, and branch"):
    val workDir = TempDirs.dir()
    ProgressStore
      .default(workDir, RunKey.of("fix the flaky test"))
      .writeHeader(header())
    assertEquals(
      ResumeDetector.detect(List(workDir)),
      Some(
        InterruptedRun(
          FlowSource.Catalog("implement.sc"),
          "fix the flaky test",
          branchName("feat/resume"),
          dir = workDir,
          log = OrcaDir.progressPath(workDir, RunKey.of("fix the flaky test"))
        )
      )
    )

  test(
    "detect is None for a log with no recorded flow (a run outside the shell)"
  ):
    val workDir = TempDirs.dir()
    ProgressStore
      .default(workDir, RunKey.of("fix the flaky test"))
      .writeHeader(header(flow = None))
    assertEquals(ResumeDetector.detect(List(workDir)), None)

  test("detect offers a recorded absolute .sc file"):
    val workDir = TempDirs.dir()
    val source = FlowSource.File("/home/u/scratch/implement.sc")
    ProgressStore
      .default(workDir, RunKey.of("fix the flaky test"))
      .writeHeader(header(flow = Some(source)))
    assertEquals(ResumeDetector.detect(List(workDir)).map(_.flow), Some(source))

  test("detect drops a recorded file a resume may not run"):
    // FlowResolutionTest covers which paths those are.
    val workDir = TempDirs.dir()
    ProgressStore
      .default(workDir, RunKey.of("fix the flaky test"))
      .writeHeader(header(flow = Some(FlowSource.File("scratch/x.sc"))))
    assertEquals(ResumeDetector.detect(List(workDir)), None)

  test("detect is None for a corrupt (unparseable) log, silently"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("fix the flaky test"))
    store.writeHeader(header())
    os.write.over(store.path, "not json {{{")
    assertEquals(ResumeDetector.detect(List(workDir)), None)

  test("detect picks the newest of multiple unfinished logs by mtime"):
    val workDir = TempDirs.dir()
    val older = ProgressStore.default(workDir, RunKey.of("older prompt"))
    older.writeHeader(
      header(
        userPrompt = "older prompt",
        flow = Some(FlowSource.Catalog("a.sc"))
      )
    )
    val newer = ProgressStore.default(workDir, RunKey.of("newer prompt"))
    newer.writeHeader(
      header(
        userPrompt = "newer prompt",
        flow = Some(FlowSource.Catalog("b.sc"))
      )
    )
    // Force a distinguishable mtime order regardless of write-speed timing.
    val _ = os.mtime.set(older.path, System.currentTimeMillis() - 60000)
    assertEquals(
      ResumeDetector.detect(List(workDir)),
      Some(
        InterruptedRun(
          FlowSource.Catalog("b.sc"),
          "newer prompt",
          branchName("feat/resume"),
          dir = workDir,
          log = OrcaDir.progressPath(workDir, RunKey.of("newer prompt"))
        )
      )
    )

  test("detect reports the directory the winning log was found in"):
    val shellDir = TempDirs.dir()
    val worktree = TempDirs.dir()
    ProgressStore
      .default(worktree, RunKey.of("fix the flaky test"))
      .writeHeader(header())
    assertEquals(
      ResumeDetector.detect(List(shellDir, worktree)),
      Some(
        InterruptedRun(
          FlowSource.Catalog("implement.sc"),
          "fix the flaky test",
          branchName("feat/resume"),
          dir = worktree,
          log = OrcaDir.progressPath(worktree, RunKey.of("fix the flaky test"))
        )
      )
    )

  test("detect: the newest wins across directories, not within each"):
    val shellDir = TempDirs.dir()
    val worktree = TempDirs.dir()
    val older = ProgressStore.default(worktree, RunKey.of("older prompt"))
    older.writeHeader(
      header(
        userPrompt = "older prompt",
        flow = Some(FlowSource.Catalog("a.sc"))
      )
    )
    ProgressStore
      .default(shellDir, RunKey.of("newer prompt"))
      .writeHeader(
        header(
          userPrompt = "newer prompt",
          flow = Some(FlowSource.Catalog("b.sc"))
        )
      )
    val _ = os.mtime.set(older.path, System.currentTimeMillis() - 60000)
    // The winner is in the FIRST directory here, the mirror of the case above.
    assertEquals(
      ResumeDetector.detect(List(shellDir, worktree)),
      Some(
        InterruptedRun(
          FlowSource.Catalog("b.sc"),
          "newer prompt",
          branchName("feat/resume"),
          dir = shellDir,
          log = OrcaDir.progressPath(shellDir, RunKey.of("newer prompt"))
        )
      )
    )

  test("detect: an unreadable directory costs only its own logs"):
    val shellDir = TempDirs.dir()
    ProgressStore
      .default(shellDir, RunKey.of("fix the flaky test"))
      .writeHeader(header())
    // `.orca` present but not listable — the scan must still offer the log it
    // can read rather than dropping the whole thing.
    val unreadable = TempDirs.dir()
    os.makeDir.all(unreadable / ".orca")
    os.perms.set(unreadable / ".orca", "---------")
    assume(
      scala.util.Try(os.list(unreadable / ".orca")).isFailure,
      "needs a user that file permissions apply to"
    )
    try
      assertEquals(
        ResumeDetector.detect(List(shellDir, unreadable)),
        Some(
          InterruptedRun(
            FlowSource.Catalog("implement.sc"),
            "fix the flaky test",
            branchName("feat/resume"),
            dir = shellDir,
            log =
              OrcaDir.progressPath(shellDir, RunKey.of("fix the flaky test"))
          )
        )
      )
    finally os.perms.set(unreadable / ".orca", "rwxr-xr-x")
