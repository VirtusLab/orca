package orca.sessions

import munit.FunSuite
import orca.agents.BackendTag
import orca.{RunKey, StagePath, WorkspaceWrite}
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
      stage: StagePath = StagePath.FlowBody,
      id: String = "uuid",
      seed: String = "brief"
  ): SessionRecord =
    SessionRecord(
      name = name,
      stage = stage,
      id = id,
      seed = seed,
      resumeWireId = None,
      backend = BackendTag.ClaudeCode
    )

  test("records() is empty before anything is written"):
    val store = SessionStore.default(TempDirs.dir(), RunKey.of("p"))
    assertEquals(store.records(), Nil)

  test("upsert writes a record a fresh store over the same dir reads back"):
    val dir = TempDirs.dir()
    val r = record()
    SessionStore.default(dir, RunKey.of("p")).upsert(r)
    assertEquals(SessionStore.default(dir, RunKey.of("p")).records(), List(r))

  test("upsert at the same key replaces the record"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    store.upsert(
      record(
        stage = StagePath.FlowBody.child("Task 1", 0),
        id = "first",
        seed = "old"
      )
    )
    val second = record(
      stage = StagePath.FlowBody.child("Task 1", 0),
      id = "second",
      seed = "new"
    )
    store.upsert(second)
    assertEquals(store.records(), List(second))

  test("a differing minting stage is a different key"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    val first =
      record(stage = StagePath.FlowBody.child("Task 1", 0), id = "uuid-0")
    val second =
      record(stage = StagePath.FlowBody.child("Task 2", 0), id = "uuid-1")
    store.upsert(first)
    store.upsert(second)
    assertEquals(store.records(), List(first, second))

  test("a differing name in one stage is a different key"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    val implementer =
      record(stage = StagePath.FlowBody.child("Task 1", 0), id = "uuid-i")
    val planner = record(
      name = "planner",
      stage = StagePath.FlowBody.child("Task 1", 0),
      id = "uuid-p"
    )
    store.upsert(implementer)
    store.upsert(planner)
    assertEquals(store.records(), List(implementer, planner))

  test("the optional wire id round-trips"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    val withWire = record().copy(resumeWireId = Some("ses_server_123"))
    store.upsert(withWire)
    assertEquals(store.records(), List(withWire))

  test("an unset wire id is written as an explicit null"):
    // The file is read by a person debugging a resume, so the key set does not
    // depend on how far the run got.
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    store.upsert(record())
    val written = os.read(store.path)
    assert(written.contains("\"resumeWireId\":null"), written)

  test("a file that does not parse reads as no records"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    store.upsert(record())
    os.write.over(store.path, "not json {{{")
    assertEquals(store.records(), Nil)

  test(
    "a record with an unknown backend tag makes the file read as no records"
  ):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    store.upsert(record().copy(backend = BackendTag.Codex))
    val written = os.read(store.path)
    assert(written.contains("\"backend\":\"Codex\""), written)
    os.write.over(
      store.path,
      written.replace("\"backend\":\"Codex\"", "\"backend\":\"Bogus\"")
    )
    assertEquals(store.records(), Nil)

  test("discard removes the file"):
    val dir = TempDirs.dir()
    val store = SessionStore.default(dir, RunKey.of("p"))
    store.upsert(record())
    store.discard()
    assert(!os.exists(store.path))
    assertEquals(store.records(), Nil)

  test("discard on an absent file is a no-op"):
    SessionStore.default(TempDirs.dir(), RunKey.of("p")).discard()

  test("records survive the failure teardown's discard of the working tree"):
    val dir = GitRepo.seeded()
    val store = SessionStore.default(dir, RunKey.of("p"))
    val r = record()
    store.upsert(r)
    new OsGitTool(dir).discardUncommitted(UntrackedFiles.Remove)
    assertEquals(store.records(), List(r))

  test("records survive the resume-time stash of a dirty tree"):
    val dir = GitRepo.seeded()
    val store = SessionStore.default(dir, RunKey.of("p"))
    val r = record()
    store.upsert(r)
    os.write.over(dir / "dirty.txt", "uncommitted")
    new OsGitTool(dir).ensureClean("orca: pre-resume")
    assertEquals(store.records(), List(r))
