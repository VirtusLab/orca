package orca

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
          description = "null pointer risk",
          file = Some("Foo.scala"),
          line = Some(42),
          suggestion = Some("add a null check")
        ),
        ReviewIssue(
          severity = Severity.Info,
          confidence = 0.4,
          description = "stylistic nitpick",
          file = None,
          line = None,
          suggestion = None
        )
      ),
      summary = "one critical, one info"
    )
    val json = writeToString(original)
    val parsed = readFromString[ReviewResult](json)
    assertEquals(parsed, original)

  private val sampleIssue =
    ReviewIssue(Severity.Warning, 0.5, "style", None, None, None)

  test("IgnoredIssues ++ concatenates entries"):
    val a = IgnoredIssues(List(IgnoredIssue(sampleIssue, "accepted")))
    val b = IgnoredIssues(List(IgnoredIssue(sampleIssue, "deferred")))
    assertEquals((a ++ b).issues.size, 2)

  test("IgnoredIssues.format renders severity, description, and reason"):
    val issues = IgnoredIssues(List(IgnoredIssue(sampleIssue, "accepted")))
    assertEquals(issues.format, "- [Warning] style: accepted")
