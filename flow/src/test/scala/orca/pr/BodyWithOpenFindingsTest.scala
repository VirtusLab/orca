package orca.pr

import munit.FunSuite
import orca.plan.Title
import orca.review.{Location, OpenFinding, OpenFindings, OpenReason}

/** The section's own text. That a PR body carries it at all is
  * [[OpenPrFromBranchTest]] and [[OpenPrIfGitHubTest]].
  */
class BodyWithOpenFindingsTest extends FunSuite:

  test("each open finding is one bullet with its title and reason"):
    val open = OpenFindings(
      List(
        OpenFinding(
          Title("Null check missing"),
          OpenReason.CapReached(5),
          None
        ),
        OpenFinding(Title("Rename foo"), OpenReason.NoFixes, None)
      )
    )
    val rendered = bodyWithOpenFindings("Body", open)
    assert(rendered.startsWith("Body\n\n## Open review findings\n"), rendered)
    // The lead has to own up to the fixer's declines and to a review that
    // never ran: the loop returns both in the same list as what stayed open at
    // the cap.
    assert(rendered.contains("including findings the fixer declined"), rendered)
    assert(rendered.contains("a review the run could not run"), rendered)
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
          Title("Null check missing"),
          OpenReason.NoFixes,
          Some(Location("src/main/Foo.scala", Some(42)))
        )
      )
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
          Title("Rename\n  foo"),
          OpenReason.Declined("out of\n  scope:\nsee plan"),
          None
        )
      )
    )
    val rendered = bodyWithOpenFindings("Body", open)
    assert(rendered.endsWith("- Rename foo — out of scope: see plan"), rendered)
