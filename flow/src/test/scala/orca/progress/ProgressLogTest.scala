package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import munit.FunSuite
import orca.agents.JsonData
import orca.gitref.CommitHash
import orca.testkit
import orca.util.RawJson

class ProgressLogTest extends FunSuite:

  private def roundTrip[A](value: A)(using jd: JsonData[A]): A =
    readFromString[A](writeToString(value)(using jd.codec))(using jd.codec)

  private def header(
      branch: String,
      branchMode: BranchMode = BranchMode.Created,
      flow: Option[FlowSource] = None
  ): ProgressHeader =
    ProgressHeader(
      startingBranch = Some(testkit.branchName("main")),
      branch = testkit.branchName(branch),
      branchMode = branchMode,
      userPrompt = "fix the flaky test",
      flow = flow,
      startingCommit =
        CommitHash.from("0badc0ffee0ddf00d1234567890abcdef1234567").get
    )

  test("ProgressLog round-trips through JsonData codec"):
    val log = ProgressLog(
      header = header(
        "feat/my-feature",
        flow = Some(FlowSource.Catalog("implement.sc"))
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
      ),
      published = Some(PublishedWork("https://example.test/pr/1"))
    )
    assertEquals(roundTrip(log), log)

  test("ProgressHeader round-trips branchMode = Reused (skip-branch mode)"):
    val log =
      ProgressLog(header("my-work", BranchMode.Reused), Nil, None)
    assertEquals(roundTrip(log).header.branchMode, BranchMode.Reused)

  test("ProgressHeader round-trips a detached start (no startingBranch)"):
    val detached = header("feat/x").copy(startingBranch = None)
    assertEquals(roundTrip(detached), detached)

  test("a branch that isn't a valid branch name fails to decode"):
    // Hand-editable like the commit below; only a valid name may reach git.
    val codec = summon[JsonData[ProgressLog]].codec
    val json = writeToString(ProgressLog(header("feat/x"), Nil, None))(using
      codec
    ).replace("\"feat/x\"", "\"HEAD\"")
    intercept[JsonReaderException](
      readFromString[ProgressLog](json)(using
        codec
      ): Unit
    )

  test("a startingCommit that isn't a commit hash fails to decode"):
    // The header is committed, hand-editable content; only a hash may reach
    // git as a diff base, so anything else fails the whole log.
    val codec = summon[JsonData[ProgressLog]].codec
    val json = writeToString(ProgressLog(header("feat/x"), Nil, None))(using
      codec
    ).replace("0badc0ffee0ddf00d1234567890abcdef1234567", "--output=/etc/pw")
    intercept[JsonReaderException](
      readFromString[ProgressLog](json)(using
        codec
      ): Unit
    )

  test("a header missing userPrompt fails to decode"):
    val codec = summon[JsonData[ProgressLog]].codec
    val json = writeToString(ProgressLog(header("feat/x"), Nil, None))(using
      codec
    ).replace(""""userPrompt":"fix the flaky test",""", "")
    intercept[JsonReaderException](
      readFromString[ProgressLog](json)(using
        codec
      ): Unit
    )
