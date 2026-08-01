package orca.progress

import munit.FunSuite
import orca.WorkspaceWrite
import orca.testkit.TempDirs

class ProgressScanTest extends FunSuite:

  // All progress-log writes require a WorkspaceWrite token; mint one for the
  // suite, mirroring ProgressStoreTest.
  given WorkspaceWrite = WorkspaceWrite.unsafe

  private val header = ProgressHeader(
    startingBranch = "main",
    branch = "feat/some-feature",
    promptHash = "abc123def456",
    branchMode = BranchMode.Created
  )

  test("progressLogPaths is empty when .orca doesn't exist"):
    assertEquals(ProgressScan.progressLogPaths(TempDirs.dir()), Nil)

  test("progressLogPaths lists only progress-<12 hex>.json files"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, "my prompt")
    store.writeHeader(header)
    List(
      "settings.properties",
      "progress-abc.json", // too short a hash
      "progress-abc123def456.txt", // wrong extension
      "progress-ABC123DEF456.json" // uppercase isn't the hash charset
    ).foreach(name => os.write(workDir / ".orca" / name, "{}"))
    assertEquals(ProgressScan.progressLogPaths(workDir), List(store.path))

  test("progressLogPaths excludes a symlinked log file"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, "my prompt")
    store.writeHeader(header)
    val outside = TempDirs.dir() / "elsewhere.json"
    os.write(outside, "{}")
    os.remove(store.path): Unit
    os.symlink(store.path, outside)
    assertEquals(ProgressScan.progressLogPaths(workDir), Nil)

  test("progressLogPaths is empty when .orca itself is a symlink"):
    val workDir = TempDirs.dir()
    val real = TempDirs.dir()
    ProgressStore.default(real, "my prompt").writeHeader(header)
    os.symlink(workDir / ".orca", real / ".orca")
    assertEquals(ProgressScan.progressLogPaths(workDir), Nil)

  test("progressLogPaths lists every log, not just one"):
    val workDir = TempDirs.dir()
    val one = ProgressStore.default(workDir, "one")
    one.writeHeader(header)
    val two = ProgressStore.default(workDir, "two")
    two.writeHeader(header)
    // As a Set: `os.list` order is unspecified, and the scan promises none.
    assertEquals(
      ProgressScan.progressLogPaths(workDir).toSet,
      Set(one.path, two.path)
    )
