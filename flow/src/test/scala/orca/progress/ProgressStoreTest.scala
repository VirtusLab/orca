package orca.progress

import munit.FunSuite
import orca.{RunKey, WorkspaceWrite}
import orca.util.RawJson
import orca.gitref.CommitHash
import orca.testkit.{TempDirs, branchName}

class ProgressStoreTest extends FunSuite:

  // All mutating calls require a WorkspaceWrite token; mint one for the suite.
  given WorkspaceWrite = WorkspaceWrite.unsafe

  private val header = ProgressHeader(
    startingBranch = Some(branchName("main")),
    branch = branchName("feat/some-feature"),
    branchMode = BranchMode.Created,
    userPrompt = "my prompt",
    flowName = None,
    startingCommit = CommitHash.from("0" * 40).get
  )

  test("writeHeader then load returns the header with empty entries"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    val loaded = store.load()
    assertEquals(loaded, Some(ProgressLog(header, Nil, None)))

  test(
    "upsertEntry with same id replaces (last write wins), different id appends"
  ):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)

    val a =
      StageEntry(
        id = "stage-1",
        name = "First",
        resultJson = RawJson("""{"v":1}""")
      )
    val aPrime =
      StageEntry(
        id = "stage-1",
        name = "First",
        resultJson = RawJson("""{"v":2}""")
      )
    val b =
      StageEntry(
        id = "stage-2",
        name = "Second",
        resultJson = RawJson("""{"v":3}""")
      )

    store.upsertEntry(a)
    store.upsertEntry(aPrime) // same id — should replace a
    store.upsertEntry(b) // different id — should append

    val loaded = store.load()
    assertEquals(loaded.map(_.entries), Some(List(aPrime, b)))

  test("the persisted file embeds resultJson verbatim, not string-escaped"):
    // `resultJson` is a RawJson: the stage result lands in the file as a JSON
    // subtree (`"resultJson":{…}`), not an escaped string blob — keeping the
    // on-disk log directly readable when debugging.
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    store.upsertEntry(
      StageEntry(
        id = "stage-1",
        name = "First",
        resultJson = RawJson("""{"v":1}""")
      )
    )
    val contents = os.read(store.path)
    assert(contents.contains(""""resultJson":{"v":1}"""), contents)

  test("load collapses a corrupt file to None"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    os.write.over(store.path, "not json {{{")
    assertEquals(store.load(), None: Option[ProgressLog])

  test("upsertEntry before writeHeader throws the absent-log message"):
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    val ex = intercept[IllegalStateException]:
      store.upsertEntry(
        StageEntry(id = "stage-1", name = "First", resultJson = RawJson("{}"))
      )
    assert(
      ex.getMessage.contains("before writeHeader"),
      s"expected the absent-log wording; got: ${ex.getMessage}"
    )

  test(
    "upsertEntry against a corrupted-but-present log surfaces a corruption-specific message, not the before-writeHeader lie"
  ):
    // A log that exists but is torn/corrupted mid-run must not be misreported
    // as "upsertEntry called before writeHeader" — that message is wrong when
    // writeHeader plainly did run (the file exists).
    val workDir = TempDirs.dir()
    val store = ProgressStore.default(workDir, RunKey.of("my prompt"))
    store.writeHeader(header)
    os.write.over(store.path, "not json {{{")
    val ex = intercept[IllegalStateException]:
      store.upsertEntry(
        StageEntry(id = "stage-1", name = "First", resultJson = RawJson("{}"))
      )
    assert(
      !ex.getMessage.contains("before writeHeader"),
      s"corruption must not be misreported as a never-happened protocol " +
        s"violation; got: ${ex.getMessage}"
    )
    assert(
      ex.getMessage.contains(store.path.toString),
      s"expected a corruption-specific message; got: ${ex.getMessage}"
    )
