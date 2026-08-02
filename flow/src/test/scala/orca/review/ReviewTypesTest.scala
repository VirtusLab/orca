package orca.review

import orca.plan.Title
import orca.agents.{AgentInput, given}
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
          location = Some(Location("Foo.scala", Some(42))),
          suggestion = Some("add a null check")
        ),
        ReviewIssue(
          severity = Severity.Info,
          confidence = 0.4,
          title = Title("Stylistic nitpick"),
          description = "stylistic nitpick",
          location = None,
          suggestion = None
        )
      )
    )
    val json = writeToString(original)
    val parsed = readFromString[ReviewResult](json)
    assertEquals(parsed, original)

  test("the fix prompt keeps a suggestion line that starts with `|`"):
    // `formatIssue` wraps a suggestion with its own line breaks and indents
    // preserved, so a quoted margin block reaches the prompt as a `|` line.
    val request = FixRequest(
      "fix these",
      List(
        ReviewIssue(
          severity = Severity.Warning,
          confidence = 0.9,
          title = Title("Mangled quote"),
          description = "ignored by formatIssue",
          location = None,
          suggestion = Some("use:\n  |a| b|")
        )
      )
    )
    assert(
      summon[AgentInput[FixRequest]].serialize(request).contains("\n  |a| b|")
    )

  test("IgnoredIssues ++ concatenates entries"):
    val a = IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "accepted")))
    val b = IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "deferred")))
    assertEquals((a ++ b).issues.size, 2)

  test("IgnoredIssues.format renders title and reason"):
    val issues =
      IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "accepted")))
    assertEquals(issues.format, "- Style nit: accepted")
