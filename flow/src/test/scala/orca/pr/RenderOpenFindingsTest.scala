package orca.pr

import munit.FunSuite
import orca.plan.Title
import orca.review.{IgnoredIssue, IgnoredIssues}

class RenderOpenFindingsTest extends FunSuite:

  test("nothing open renders no section"):
    assertEquals(renderOpenFindings(IgnoredIssues(Nil)), None)

  test("each open finding is one bullet with its title and reason"):
    val open = IgnoredIssues(
      List(
        IgnoredIssue(Title("Null check missing"), "max iterations (5) reached"),
        IgnoredIssue(Title("Rename foo"), "fixer reported no fixes")
      )
    )
    val rendered = renderOpenFindings(open).getOrElse(fail("no section"))
    assert(rendered.startsWith("## Open review findings\n"), rendered)
    // The lead has to own up to the fixer's declines: the loop returns them in
    // the same list as what stayed open at the cap.
    assert(rendered.contains("including findings the fixer declined"), rendered)
    assertEquals(
      rendered.linesIterator.filter(_.startsWith("- ")).toList,
      List(
        "- Null check missing — max iterations (5) reached",
        "- Rename foo — fixer reported no fixes"
      )
    )

  test("a multi-line reason is collapsed onto its bullet"):
    val open = IgnoredIssues(
      List(IgnoredIssue(Title("Rename foo"), "out of\n  scope:\nsee plan"))
    )
    val rendered = renderOpenFindings(open).getOrElse(fail("no section"))
    assert(rendered.endsWith("- Rename foo — out of scope: see plan"), rendered)

  test("a multi-line title is collapsed onto its bullet"):
    val open = IgnoredIssues(
      List(IgnoredIssue(Title("Rename\n  foo"), "out of scope"))
    )
    val rendered = renderOpenFindings(open).getOrElse(fail("no section"))
    assert(rendered.endsWith("- Rename foo — out of scope"), rendered)
