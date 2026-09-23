package orca.pr

import munit.FunSuite
import orca.plan.Title
import orca.review.{
  FindingId,
  Location,
  OpenFinding,
  OpenFindings,
  OpenReason,
  SkippedReview
}

/** The section's own text. That a PR body carries it at all is
  * [[OpenPrFromBranchTest]] and [[OpenPrIfGitHubTest]].
  */
class BodyWithOpenFindingsTest extends FunSuite:

  test("each open finding is one bullet with its title and reason"):
    val open = OpenFindings(
      List(
        OpenFinding(
          FindingId("R1.I1.1"),
          Title("Null check missing"),
          OpenReason.CapReached(5),
          None
        ),
        OpenFinding(
          FindingId("R1.I1.2"),
          Title("Rename foo"),
          OpenReason.NoFixes,
          None
        )
      ),
      skipped = None
    )
    val rendered = bodyWithOpenFindings("Body", open)
    assert(rendered.startsWith("Body\n\n## Open review findings\n"), rendered)
    // The lead has to own up to the fixer's declines: the loop returns them in
    // the same list as what stayed open at the cap.
    assert(rendered.contains("including findings the fixer declined"), rendered)
    assertEquals(
      rendered.linesIterator.filter(_.startsWith("- ")).toList,
      List(
        s"- Null check missing — ${OpenReason.CapReached(5).describe}",
        s"- Rename foo — ${OpenReason.NoFixes.describe}"
      )
    )

  test("a bullet names where the finding points when the reviewer said"):
    // The reviewer that first reported it named the place, and an entry seeded
    // from an earlier loop still carries it — so the PR body can point a reader
    // at the code.
    val open = OpenFindings(
      List(
        OpenFinding(
          FindingId("R1.I1.1"),
          Title("Null check missing"),
          OpenReason.NoFixes,
          Some(Location("src/main/Foo.scala", Some(42)))
        )
      ),
      skipped = None
    )
    val rendered = bodyWithOpenFindings("Body", open)
    assert(
      rendered.endsWith(
        s"- Null check missing (`src/main/Foo.scala:42`) — " +
          OpenReason.NoFixes.describe
      ),
      rendered
    )

  test("a multi-line title and reason are collapsed onto one bullet"):
    val open = OpenFindings(
      List(
        OpenFinding(
          FindingId("R1.I1.1"),
          Title("Rename\n  foo"),
          OpenReason.Declined("out of\n  scope:\nsee plan"),
          None
        )
      ),
      skipped = None
    )
    val rendered = bodyWithOpenFindings("Body", open)
    assert(rendered.endsWith("- Rename foo — out of scope: see plan"), rendered)

  test("a skipped review is said, so the PR does not read as reviewed clean"):
    val open = OpenFindings(Nil, skipped = Some(SkippedReview.NoStartingCommit))
    assertEquals(
      bodyWithOpenFindings("Body", open),
      "Body\n\n## Open review findings\n\n" +
        s"The review did not run: ${SkippedReview.NoStartingCommit.describe}."
    )
