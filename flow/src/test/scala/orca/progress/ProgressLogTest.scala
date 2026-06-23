package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import munit.FunSuite
import orca.llm.JsonData

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
          resultJson = """{"ok":true}"""
        ),
        StageEntry(
          id = "stage-2",
          name = "Implement",
          resultJson = """{"files":["a.scala"]}"""
        )
      )
    )
    assertEquals(roundTrip(log), log)
