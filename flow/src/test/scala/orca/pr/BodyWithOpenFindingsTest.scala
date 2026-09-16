package orca.pr

import munit.FunSuite
import orca.plan.Title
import orca.review.{IgnoredIssue, IgnoredIssues}

/** The section's own text. That a PR body carries it at all is
  * [[OpenPrFromBranchTest]] and [[OpenPrIfGitHubTest]].
  */
class BodyWithOpenFindingsTest extends FunSuite:

  test("each open finding is one bullet with its title and reason"):
    val open = IgnoredIssues(
      List(
        IgnoredIssue(Title("Null check missing"), "max iterations (5) reached"),
        IgnoredIssue(Title("Rename foo"), "fixer reported no fixes")
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
        "- Null check missing — max iterations (5) reached",
        "- Rename foo — fixer reported no fixes"
      )
    )

  test("a multi-line title and reason are collapsed onto one bullet"):
    val open = IgnoredIssues(
      List(IgnoredIssue(Title("Rename\n  foo"), "out of\n  scope:\nsee plan"))
    )
    val rendered = bodyWithOpenFindings("Body", open)
    assert(rendered.endsWith("- Rename foo — out of scope: see plan"), rendered)
