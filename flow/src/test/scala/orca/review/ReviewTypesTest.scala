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
          title = Title("Null pointer risk"),
          description = "null pointer risk",
          location = Some(Location("Foo.scala", Some(42))),
          suggestion = Some("add a null check")
        ),
        ReviewIssue(
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

  test("an empty picker selection still decodes"):
    // The schema forbids it, but only backends that enforce the schema on the
    // wire are bound by that — the rest reach `ReviewerSelector`'s fallback
    // through this decode.
    assertEquals(
      readFromString[SelectedReviewers]("""{"names":[]}"""),
      SelectedReviewers(Nil)
    )

  test("the fix prompt keeps a suggestion line that starts with `|`"):
    // `FixRequest`'s renderer keeps a suggestion's own line breaks and indents,
    // so a quoted margin block reaches the prompt as a `|` line.
    val request = FixRequest(
      "fix these",
      KeyedIssue.forAgent(
        0,
        List(
          ReviewIssue(
            title = Title("Mangled quote"),
            description = "the quote is mangled",
            location = None,
            suggestion = Some("use:\n  |a| b|")
          )
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
