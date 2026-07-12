package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import munit.FunSuite
import orca.agents.JsonData
import orca.util.RawJson

class ProgressLogTest extends FunSuite:

  private def roundTrip[A](value: A)(using jd: JsonData[A]): A =
    readFromString[A](writeToString(value)(using jd.codec))(using jd.codec)

  test("ProgressLog round-trips through JsonData codec"):
    val log = ProgressLog(
      header = ProgressHeader(
        startingBranch = "main",
        branch = "feat/my-feature",
        promptHash = "abc123def456"
      ),
      entries = List(
        StageEntry(
          id = "stage-1",
          name = "Analyse",
          resultJson = RawJson("""{"ok":true}""")
        ),
        StageEntry(
          id = "stage-2",
          name = "Implement",
          resultJson = RawJson("""{"files":["a.scala"]}""")
        )
      )
    )
    assertEquals(roundTrip(log), log)

  test("ProgressLog with sessions round-trips through JsonData codec"):
    val log = ProgressLog(
      header = ProgressHeader(
        startingBranch = "main",
        branch = "feat/sessions",
        promptHash = "abc123def456"
      ),
      entries = List(
        StageEntry(
          id = "stage-1",
          name = "Plan",
          resultJson = RawJson("""{"ok":true}""")
        )
      ),
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = "sess-uuid-1",
          seed = "plan brief"
        ),
        SessionRecord(
          name = "s",
          occurrence = 1,
          id = "sess-uuid-2",
          seed = "other seed"
        )
      )
    )
    assertEquals(roundTrip(log), log)

  test("SessionRecord round-trips with resumeWireId = Some(...)"):
    val log = ProgressLog(
      header = ProgressHeader("main", "feat/server", "abc123"),
      entries = Nil,
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = "client-uuid",
          seed = "brief",
          resumeWireId = Some("ses_server_123")
        )
      )
    )
    assertEquals(roundTrip(log), log)

  test("SessionRecord round-trips with backend = Some(...)"):
    val log = ProgressLog(
      header = ProgressHeader("main", "feat/tagged", "abc123"),
      entries = Nil,
      sessions = List(
        SessionRecord(
          name = "s",
          occurrence = 0,
          id = "client-uuid",
          seed = "brief",
          resumeWireId = Some("ses_server_123"),
          backend = Some("Codex")
        )
      )
    )
    assertEquals(roundTrip(log), log)

  test(
    "SessionRecord JSON without resumeWireId/backend fields decodes both to None"
  ):
    // A record persisted before a run learns the wire id carries neither
    // field; both are optional in the codec and default to None.
    val json =
      """{"header":{"startingBranch":"main","branch":"feat/old","promptHash":"abc"},""" +
        """"entries":[],"sessions":[{"name":"s","occurrence":0,"id":"u","seed":"s"}]}"""
    val codec = summon[JsonData[ProgressLog]].codec
    val decoded = readFromString[ProgressLog](json)(using codec)
    assertEquals(decoded.sessions.head.resumeWireId, None)
    assertEquals(decoded.sessions.head.backend, None)

  test("SessionRecord JSON without its name/occurrence key fails to decode"):
    // name/occurrence key the record; a log without them is not usable, so
    // decoding fails (and the run starts a fresh log) rather than silently
    // colliding every record on a default key.
    val json =
      """{"header":{"startingBranch":"main","branch":"feat/old","promptHash":"abc"},""" +
        """"entries":[],"sessions":[{"index":0,"id":"u","seed":"s"}]}"""
    val codec = summon[JsonData[ProgressLog]].codec
    val _ = intercept[JsonReaderException](
      readFromString[ProgressLog](json)(using codec)
    )

  test(
    "ProgressLog JSON without a sessions field decodes to empty sessions list (back-compat)"
  ):
    // JSON produced by the old format (before sessions field existed)
    val oldJson =
      """{"header":{"startingBranch":"main","branch":"feat/old","promptHash":"abc123"},"entries":[]}"""
    val codec = summon[JsonData[ProgressLog]].codec
    val decoded = readFromString[ProgressLog](oldJson)(using codec)
    assertEquals(decoded.sessions, Nil)
    assertEquals(decoded.header.branch, "feat/old")
