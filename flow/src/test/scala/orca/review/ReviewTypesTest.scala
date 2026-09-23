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
          suggestion = Some("add a null check"),
          reopens = None
        ),
        ReviewFinding(
          title = Title("Stylistic nitpick"),
          description = "stylistic nitpick",
          location = None,
          suggestion = None,
          reopens = None
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
            suggestion = Some("use:\n  |a| b|"),
            reopens = None
          )
        )
      )
    )
    assert(
      summon[AgentInput[FixRequest]].serialize(request).contains("\n  |a| b|")
    )

  test(
    "the fix prompt puts the instructions and reply format above the findings"
  ):
    // Every fix turn arrives in this shape; the label is what separates the
    // caller's instructions from the findings under them.
    val request = FixRequest(
      "fix these",
      KeyedFinding.forAgent(
        0,
        List(
          ReviewFinding(
            title = Title("Leaks a handle"),
            description = "the handle is never closed",
            location = None,
            suggestion = None,
            reopens = None
          )
        )
      )
    )
    assert(
      summon[AgentInput[FixRequest]]
        .serialize(request)
        .startsWith(
          s"fix these\n\n${FixOutcome.ReplyFormat}\n\n" +
            "Findings to fix:\nI1.1 Leaks a handle"
        ),
      summon[AgentInput[FixRequest]].serialize(request)
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
    // A stage result a resume replays and the PR body then reads back, over
    // opaque `FindingId` and `Title`.
    val original = OpenFindings(
      List(
        OpenFinding(
          FindingId("R1.I1.1"),
          Title("Null check missing"),
          OpenReason.CapReached(3),
          None
        )
      ),
      skipped = Some(SkippedReview.NoStartingCommit)
    )
    assertEquals(
      readFromString[OpenFindings](writeToString(original)),
      original
    )
