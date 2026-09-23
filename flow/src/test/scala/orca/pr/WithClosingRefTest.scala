package orca.pr

import munit.FunSuite
import orca.tools.IssueHandle

class WithClosingRefTest extends FunSuite:

  private val issue = IssueHandle("acme", "widgets", 42)

  test("appends the closing line"):
    assertEquals(
      withClosingRef("Body", issue),
      "Body\n\nCloses acme/widgets#42."
    )

  test("drops the summariser's own closing line for the same issue"):
    val body = "Body\n\nCloses #42\ncloses acme/widgets#42."
    assertEquals(withClosingRef(body, issue), "Body\n\nCloses acme/widgets#42.")

  test("drops closing lines in other keyword, list and URL forms"):
    val body =
      """Body
        |
        |- Fixes #42 (null deref)
        |Resolves https://github.com/acme/widgets/issues/42""".stripMargin
    assertEquals(withClosingRef(body, issue), "Body\n\nCloses acme/widgets#42.")

  test("keeps closing lines for other issues"):
    val body = "Body\n\nCloses #7\nCloses #420\nCloses other/repo#42"
    assertEquals(
      withClosingRef(body, issue),
      s"$body\n\nCloses acme/widgets#42."
    )
