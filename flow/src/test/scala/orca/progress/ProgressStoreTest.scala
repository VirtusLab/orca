package orca.progress

import munit.FunSuite
import orca.InStage

class ProgressStoreTest extends FunSuite:

  // All mutating calls require an InStage token; mint one for the test suite.
  given InStage = InStage.unsafe

  private val header = ProgressHeader(
    startingBranch = "main",
    branch = "feat/some-feature",
    promptHash = "abc123def456"
  )

  test("writeHeader then load returns the header with empty entries"):
    val workDir = os.temp.dir()
    val store = ProgressStore.default(workDir, "my prompt")
    store.writeHeader(header)
    val loaded = store.load()
    assertEquals(loaded, Some(ProgressLog(header, Nil)))

  test(
    "appendEntry with same id upserts (last write wins), different id appends"
  ):
    val workDir = os.temp.dir()
    val store = ProgressStore.default(workDir, "my prompt")
    store.writeHeader(header)

    val a =
      StageEntry(id = "stage-1", name = "First", resultJson = """{"v":1}""")
    val aPrime =
      StageEntry(id = "stage-1", name = "First", resultJson = """{"v":2}""")
    val b =
      StageEntry(id = "stage-2", name = "Second", resultJson = """{"v":3}""")

    store.appendEntry(a)
    store.appendEntry(aPrime) // same id — should replace a
    store.appendEntry(b) // different id — should append

    val loaded = store.load()
    assertEquals(loaded.map(_.entries), Some(List(aPrime, b)))

  test("load returns None when no file exists"):
    val workDir = os.temp.dir()
    val store = ProgressStore.default(workDir, "my prompt")
    assertEquals(store.load(), None: Option[ProgressLog])

  test("load returns None for a corrupt file (no throw)"):
    val workDir = os.temp.dir()
    val store = ProgressStore.default(workDir, "my prompt")
    // Manually write garbage to the expected path location
    val path = workDir / ".orca"
    os.makeDir.all(path)
    // Write garbage to every possible progress file via direct os.write
    // The store's path is deterministic for a given prompt, so we can
    // reconstruct it by writing to any file there — but we need the exact path.
    // Instead, call writeHeader so the file exists, then overwrite with garbage.
    store.writeHeader(header)
    // Overwrite with non-JSON content
    val files = os.list(path).filter(_.last.startsWith("progress-"))
    assert(files.nonEmpty, "expected at least one progress file")
    os.write.over(files.head, "not json {{{")
    assertEquals(store.load(), None: Option[ProgressLog])

  test("default path is <workDir>/.orca/progress-<12hexchars>.json"):
    val workDir = os.temp.dir()
    val store = ProgressStore.default(workDir, "test prompt")
    store.writeHeader(header)
    val orcaDir = workDir / ".orca"
    val files = os.list(orcaDir).filter(_.last.startsWith("progress-"))
    assert(files.size == 1, s"expected exactly one progress file, got $files")
    val filename = files.head.last
    // filename must match progress-<12 hex chars>.json
    assert(
      filename.matches("progress-[0-9a-f]{12}\\.json"),
      s"unexpected filename: $filename"
    )

  test("default path is deterministic for a given prompt"):
    val workDir1 = os.temp.dir()
    val workDir2 = os.temp.dir()
    val store1 = ProgressStore.default(workDir1, "deterministic prompt")
    val store2 = ProgressStore.default(workDir2, "deterministic prompt")
    store1.writeHeader(header)
    store2.writeHeader(header)
    val name1 = os.list(workDir1 / ".orca").head.last
    val name2 = os.list(workDir2 / ".orca").head.last
    assertEquals(name1, name2)
