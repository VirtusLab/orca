package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
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
        promptHash = "abc123def456",
        branchMode = BranchMode.Created
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

  test("a log an older orca wrote, carrying session records, still decodes"):
    // Durable session records live in `.orca/cache/` now, so the log's
    // `sessions` array is an unknown key: skipped, rather than failing the
    // resume of an in-flight run started under an older build. Those records
    // are lost with it, and the resumed run re-mints and re-seeds.
    val json =
      """{"header":{"startingBranch":"main","branch":"feat/old","promptHash":"abc","branchMode":{"type":"Created"}},""" +
        """"entries":[],"sessions":[{"name":"s","stage":"","id":"u","seed":"s"}]}"""
    val codec = summon[JsonData[ProgressLog]].codec
    val decoded = readFromString[ProgressLog](json)(using codec)
    assertEquals(decoded.header.branch, "feat/old")
    assertEquals(decoded.entries, Nil)

  test("ProgressHeader round-trips userPrompt/flowName when set"):
    val log = ProgressLog(
      header = ProgressHeader(
        startingBranch = "main",
        branch = "feat/resume",
        promptHash = "abc123def456",
        branchMode = BranchMode.Created,
        userPrompt = Some("fix the flaky test"),
        flowName = Some("implement.sc")
      ),
      entries = Nil
    )
    assertEquals(roundTrip(log), log)
    assertEquals(roundTrip(log).header.userPrompt, Some("fix the flaky test"))
    assertEquals(roundTrip(log).header.flowName, Some("implement.sc"))

  test("ProgressHeader round-trips startingCommit when set"):
    val log = ProgressLog(
      header = ProgressHeader(
        startingBranch = "main",
        branch = "feat/whole-run",
        promptHash = "abc123def456",
        branchMode = BranchMode.Created,
        startingCommit = Some("0badc0ffee0ddf00d1234567890abcdef1234567")
      ),
      entries = Nil
    )
    assertEquals(roundTrip(log), log)

  test(
    "ProgressHeader JSON without userPrompt/flowName keys decodes both to None (old-format log)"
  ):
    // A header persisted before these fields existed — tolerated under
    // ProgressLog's documented tolerant decoding, so an in-flight run survives
    // an orca upgrade.
    val json =
      """{"header":{"startingBranch":"main","branch":"feat/old","promptHash":"abc","branchMode":{"type":"Created"}},""" +
        """"entries":[]}"""
    val codec = summon[JsonData[ProgressLog]].codec
    val decoded = readFromString[ProgressLog](json)(using codec)
    assertEquals(decoded.header.userPrompt, None)
    assertEquals(decoded.header.flowName, None)

  test("ProgressHeader round-trips branchMode = Reused (skip-branch mode)"):
    val log = ProgressLog(
      header = ProgressHeader(
        startingBranch = "my-work",
        branch = "my-work",
        promptHash = "abc123def456",
        branchMode = BranchMode.Reused
      ),
      entries = Nil
    )
    assertEquals(roundTrip(log), log)
    assertEquals(roundTrip(log).header.branchMode, BranchMode.Reused)
