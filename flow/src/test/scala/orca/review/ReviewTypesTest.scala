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
          confidence = Confidence.orThrow(0.95),
          title = Title("Null pointer risk"),
          description = "null pointer risk",
          location = Some(Location("Foo.scala", Some(42))),
          suggestion = Some("add a null check")
        ),
        ReviewIssue(
          severity = Severity.Info,
          confidence = Confidence.orThrow(0.4),
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

  test("a confidence outside [0,1] is rejected at decode"):
    // A percent-style reply would otherwise clear every gate bar forever.
    val json =
      """{"issues":[{"severity":"Warning","confidence":85,"title":"t",""" +
        """"description":"d","location":null,"suggestion":null}]}"""
    intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException](
      readFromString[ReviewResult](json)
    )

  test("the fix prompt keeps a suggestion line that starts with `|`"):
    // `FixRequest`'s renderer keeps a suggestion's own line breaks and indents,
    // so a quoted margin block reaches the prompt as a `|` line.
    val request = FixRequest(
      "fix these",
      List(
        ReviewIssue(
          severity = Severity.Warning,
          confidence = Confidence.orThrow(0.9),
          title = Title("Mangled quote"),
          description = "the quote is mangled",
          location = None,
          suggestion = Some("use:\n  |a| b|")
        )
      )
    )
    assert(
      summon[AgentInput[FixRequest]].serialize(request).contains("\n  |a| b|")
    )

  test("the picker prompt keeps an instruction line that starts with `|`"):
    val request = ReviewerSelectionRequest(
      taskTitle = Title("Add a check"),
      changedFiles = List("Foo.scala"),
      availableReviewers = List(ReviewerInfo("security", "security review")),
      instructions = "pick one:\n  |a| b|"
    )
    assert(
      summon[AgentInput[ReviewerSelectionRequest]]
        .serialize(request)
        .contains("\n  |a| b|")
    )

  test("IgnoredIssues.format renders title and reason"):
    val issues =
      IgnoredIssues(List(IgnoredIssue(Title("Style nit"), "accepted")))
    assertEquals(issues.format, "- Style nit: accepted")
