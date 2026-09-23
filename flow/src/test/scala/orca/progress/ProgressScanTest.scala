package orca.progress

import munit.FunSuite
import orca.{RunKey, WorkspaceWrite}
import orca.gitref.CommitHash
import orca.testkit.{TempDirs, branchName}

class ProgressScanTest extends FunSuite:

  // All progress-log writes require a WorkspaceWrite token; mint one for the
  // suite, mirroring ProgressStoreTest.
  given WorkspaceWrite = WorkspaceWrite.unsafe

  private val header = ProgressHeader(
    startingBranch = Some(branchName("main")),
    branch = branchName("feat/some-feature"),
    branchMode = BranchMode.Created,
    userPrompt = "my prompt",
    flow = None,
    startingCommit = CommitHash.from("0" * 40).get
  )

  test("progressLogPaths is empty when .orca doesn't exist"):
    assertEquals(ProgressScan.progressLogPaths(TempDirs.dir()), Nil)

  test("progressLogPaths lists only *.progress.json files under .orca/runs"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    List(
      "abc123def456.sessions.json", // another document
      "abc123def456.progress.txt", // wrong extension
      ".abc123def456.progress.json.1.tmp" // an in-flight temp file
    ).foreach(name => os.write(workDir / ".orca" / "runs" / name, "{}"))
    os.write(workDir / ".orca" / "settings.properties", "")
    assertEquals(ProgressScan.progressLogPaths(workDir), List(store.path))

  test("progressLogPaths excludes a symlinked log file"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    val outside = TempDirs.dir() / "elsewhere.json"
    os.write(outside, "{}")
    os.remove(store.path): Unit
    os.symlink(store.path, outside)
    assertEquals(ProgressScan.progressLogPaths(workDir), Nil)

  test("progressLogPaths is empty when .orca itself is a symlink"):
    val workDir = TempDirs.dir()
    val real = TempDirs.dir()
    ProgressStore.default(real, RunKey.of("my prompt")).writeHeader(header)
    os.symlink(workDir / ".orca", real / ".orca")
    assertEquals(ProgressScan.progressLogPaths(workDir), Nil)

  test("progressLogPaths is empty when .orca/runs is a symlink"):
    val workDir = TempDirs.dir()
    val real = TempDirs.dir()
    ProgressStore.default(real, RunKey.of("my prompt")).writeHeader(header)
    os.makeDir.all(workDir / ".orca")
    os.symlink(workDir / ".orca" / "runs", real / ".orca" / "runs")
    assertEquals(ProgressScan.progressLogPaths(workDir), Nil)

  test("progressLogPaths skips a directory named like a log"):
    // Only files are candidates; a same-named directory must cost itself, not
    // the readable log beside it (an empty scan reads as "no runs in flight").
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    os.makeDir.all(workDir / ".orca" / "runs" / "000000000000.progress.json")
    assertEquals(ProgressScan.progressLogPaths(workDir), List(store.path))

  test("progressLogs pairs every readable log with its header"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    val corrupt = ProgressStore.default(workDir, RunKey.of("corrupt prompt"))
    corrupt.writeHeader(header)
    os.write.over(corrupt.path, "not json {{{")
    assertEquals(
      ProgressScan.progressLogs(workDir),
      List(ScannedProgressLog(store.path, header))
    )

  test("progressLogPaths lists every log, not just one"):
    val workDir = TempDirs.dir()
    val one = ProgressStore.default(workDir, RunKey.of("one"))
    one.writeHeader(header)
    val two = ProgressStore.default(workDir, RunKey.of("two"))
    two.writeHeader(header)
    // As a Set: `os.list` order is unspecified, and the scan promises none.
    assertEquals(
      ProgressScan.progressLogPaths(workDir).toSet,
      Set(one.path, two.path)
    )
