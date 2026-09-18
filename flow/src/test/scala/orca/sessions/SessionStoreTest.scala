package orca.sessions

import munit.FunSuite
import orca.{OrcaDir, WorkspaceWrite}
import orca.testkit.{GitRepo, TempDirs}
import orca.tools.{OsGitTool, UntrackedFiles}

/** Tests for the durable-session store: its upsert-by-key semantics, and the
  * property the records exist for — surviving everything a run does to the
  * working tree between the mint and the resume that re-runs the minting stage.
  */
class SessionStoreTest extends FunSuite:

  private given WorkspaceWrite = WorkspaceWrite.unsafe

  private def record(
      name: String = "implementer",
      stage: String = "",
      id: String = "uuid",
      seed: String = "brief"
  ): SessionRecord =
    SessionRecord(name = name, stage = stage, id = id, seed = seed)

  test("records() is empty before anything is written"):
    val store = SessionStore.default(TempDirs.dir(), "p")
    assertEquals(store.records(), Nil)

  test("the file lives in the working directory's own .orca/cache"):
    // What makes a --worktree run read back its own records: the path follows
    // the directory the run happens in, which for a worktree run is the
    // worktree, exactly as the progress log's does.
    val dir = TempDirs.dir()
    assertEquals(
      SessionStore.default(dir, "p").path / os.up,
      OrcaDir.cachePath(dir)
    )

  test("upsert writes a record a fresh store over the same dir reads back"):
    val dir = TempDirs.dir()
    val r = record()
    SessionStore.default(dir, "p").upsert(r)
    assertEquals(SessionStore.default(dir, "p").records(), List(r))

  test("upsert at the same key replaces the record"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    store.upsert(record(stage = "Task 1#0", id = "first", seed = "old"))
    val second = record(stage = "Task 1#0", id = "second", seed = "new")
    store.upsert(second)
    assertEquals(store.records(), List(second))

  test("a differing minting stage is a different key"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    val first = record(stage = "Task 1#0", id = "uuid-0")
    val second = record(stage = "Task 2#0", id = "uuid-1")
    store.upsert(first)
    store.upsert(second)
    assertEquals(store.records(), List(first, second))

  test("a differing name in one stage is a different key"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    val implementer = record(stage = "Task 1#0", id = "uuid-i")
    val planner = record(name = "planner", stage = "Task 1#0", id = "uuid-p")
    store.upsert(implementer)
    store.upsert(planner)
    assertEquals(store.records(), List(implementer, planner))

  test("the optional wire id and backend tag round-trip"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    val tagged =
      record().copy(
        resumeWireId = Some("ses_server_123"),
        backend = Some("Codex")
      )
    store.upsert(tagged)
    assertEquals(store.records(), List(tagged))

  test("a record written before a run learns its wire id reads both as absent"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    store.upsert(record())
    assertEquals(store.records().head.resumeWireId, None)
    assertEquals(store.records().head.backend, None)

  test("a file that does not parse reads as no records"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    store.upsert(record())
    os.write.over(store.path, "not json {{{")
    assertEquals(store.records(), Nil)

  test("discard removes the file"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, "p")
    store.upsert(record())
    store.discard()
    assert(!os.exists(store.path))
    assertEquals(store.records(), Nil)

  test("discard on an absent file is a no-op"):
    SessionStore.default(TempDirs.dir(), "p").discard()

  test("records survive the failure teardown's discard of the working tree"):
    val dir = GitRepo.seeded()
    val store = SessionStore.default(dir, "p")
    val r = record()
    store.upsert(r)
    new OsGitTool(dir).discardUncommitted(UntrackedFiles.Remove)
    assertEquals(store.records(), List(r))

  test("records survive the resume-time stash of a dirty tree"):
    val dir = GitRepo.seeded()
    val store = SessionStore.default(dir, "p")
    val r = record()
    store.upsert(r)
    os.write.over(dir / "dirty.txt", "uncommitted")
    new OsGitTool(dir).ensureClean("orca: pre-resume")
    assertEquals(store.records(), List(r))
