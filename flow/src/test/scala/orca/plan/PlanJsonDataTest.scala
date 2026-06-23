package orca.plan

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import munit.FunSuite
import orca.llm.{BackendTag, JsonData, SessionId}

class PlanJsonDataTest extends FunSuite:

  private def roundTrip[A](value: A)(using jd: JsonData[A]): A =
    readFromString[A](writeToString(value)(using jd.codec))(using jd.codec)

  test("SessionId round-trips"):
    val id = SessionId.fresh[BackendTag.ClaudeCode.type]
    assertEquals(roundTrip(id), id)

  test("Plan round-trips as PlanLike"):
    val plan: PlanLike = Plan(
      epicId = "my-epic",
      description = "A test plan",
      tasks = List(Task(title = Title("task-1"), description = "Do something"))
    )
    val decoded = roundTrip(plan)
    assertEquals(decoded, plan)
    assert(
      decoded.isInstanceOf[Plan],
      s"Expected Plan but got ${decoded.getClass.getSimpleName}"
    )

  test("PlanWithBrief round-trips as PlanLike"):
    val plan = Plan(
      epicId = "epic-2",
      description = "Another plan",
      tasks =
        List(Task(title = Title("task-a"), description = "Do another thing"))
    )
    val pwb: PlanLike = PlanWithBrief(plan, brief = "This is the brief text.")
    val decoded = roundTrip(pwb)
    assertEquals(decoded, pwb)
    assert(
      decoded.isInstanceOf[PlanWithBrief],
      s"Expected PlanWithBrief but got ${decoded.getClass.getSimpleName}"
    )

  test("PlanLike decoding fails on unknown discriminator"):
    val jd = summon[JsonData[PlanLike]]
    val json = """{"type":"UnknownSubtype","value":{}}"""
    intercept[JsonReaderException]:
      readFromString[PlanLike](json)(using jd.codec)
