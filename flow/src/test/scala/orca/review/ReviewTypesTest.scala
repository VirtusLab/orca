package orca.review

import orca.plan.Title
import orca.agents.given
import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}

class ReviewTypesTest extends munit.FunSuite:
  test("ReviewResult round-trips through JSON"):
    val original = ReviewResult(
      issues = List(
        ReviewIssue(
          severity = Severity.Critical,
          confidence = 0.95,
          title = Title("Null pointer risk"),
          description = "null pointer risk",
          file = Some("Foo.scala"),
          line = Some(42),
          suggestion = Some("add a null check")
        ),
        ReviewIssue(
          severity = Severity.Info,
          confidence = 0.4,
          title = Title("Stylistic nitpick"),
          description = "stylistic nitpick",
          file = None,
          line = None,
          suggestion = None
        )
      )
    )
    val json = writeToString(original)
    val parsed = readFromString[ReviewResult](json)
    assertEquals(parsed, original)

  test("IgnoredIssues ++ concatenates entries"):
    val a = IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "accepted")))
    val b = IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "deferred")))
    assertEquals((a ++ b).issues.size, 2)

  test("IgnoredIssues.format renders title and reason"):
    val issues =
      IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "accepted")))
    assertEquals(issues.format, "- Style nit: accepted")
