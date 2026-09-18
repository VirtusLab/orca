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
      findings = List(
        ReviewFinding(
          title = Title("Null pointer risk"),
          description = "null pointer risk",
          location = Some(Location("Foo.scala", Some(42))),
          suggestion = Some("add a null check")
        ),
        ReviewFinding(
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
      KeyedFinding.forAgent(
        0,
        List(
          ReviewFinding(
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

  test("OpenFindings round-trips through JSON"):
    // A stage result a resume replays and the PR body then reads back, over an
    // opaque `Title`.
    val original = OpenFindings(
      List(OpenFinding(Title("Null check missing"), "max iterations reached"))
    )
    assertEquals(
      readFromString[OpenFindings](writeToString(original)),
      original
    )

  test("OpenFindings.format keeps a multi-line reason on one bullet"):
    val findings = OpenFindings(
      List(OpenFinding(Title("Style nit"), "out of\n  scope:\nsee plan"))
    )
    assertEquals(findings.format, "- Style nit: out of scope: see plan")
