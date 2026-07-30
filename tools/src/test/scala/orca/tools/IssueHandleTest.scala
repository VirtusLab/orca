package orca.tools

class IssueHandleTest extends munit.FunSuite:

  private val expected = IssueHandle("acme", "widgets", 7)

  test("parse accepts the canonical short form"):
    assertEquals(IssueHandle.parse("acme/widgets#7"), Right(expected))

  test("parse tolerates whitespace around the short form"):
    assertEquals(IssueHandle.parse("  acme/widgets#7\n"), Right(expected))

  test("parse accepts an https issue URL"):
    assertEquals(
      IssueHandle.parse("https://github.com/acme/widgets/issues/7"),
      Right(expected)
    )

  test("parse accepts an https pull-request URL"):
    // flows/review.sc funnels PR refs through IssueHandle, so /pull/ must parse.
    assertEquals(
      IssueHandle.parse("https://github.com/acme/widgets/pull/7"),
      Right(expected)
    )

  test("parse accepts an http URL"):
    assertEquals(
      IssueHandle.parse("http://github.com/acme/widgets/issues/7"),
      Right(expected)
    )

  test("parse accepts a www-prefixed URL"):
    assertEquals(
      IssueHandle.parse("https://www.github.com/acme/widgets/issues/7"),
      Right(expected)
    )

  test("parse accepts a URL with a trailing slash"):
    assertEquals(
      IssueHandle.parse("https://github.com/acme/widgets/issues/7/"),
      Right(expected)
    )

  test("parse accepts a scheme-less URL with surrounding whitespace"):
    assertEquals(
      IssueHandle.parse(" github.com/acme/widgets/issues/7 "),
      Right(expected)
    )

  test("parse rejects a URL carrying a query string or fragment"):
    assert(
      IssueHandle.parse("https://github.com/acme/widgets/issues/7?x=1").isLeft
    )
    assert(
      IssueHandle
        .parse("https://github.com/acme/widgets/issues/7#issuecomment-1")
        .isLeft
    )

  test("parse rejects owner/repo segments that are not GitHub-legal names"):
    // owner and repo end up in `gh api` paths, so separators and traversal
    // segments must not slip through either pattern.
    assert(
      IssueHandle.parse("https://github.com/acme/widgets?x=1/issues/7").isLeft
    )
    assert(
      IssueHandle.parse("https://github.com/acme/wid#gets/issues/7").isLeft
    )
    assert(IssueHandle.parse("https://github.com/../../issues/7").isLeft)
    assert(IssueHandle.parse("https://github.com/acme/../issues/7").isLeft)
    assert(IssueHandle.parse("acme/..#7").isLeft)

  test("parse accepts hyphens, dots and underscores in owner and repo names"):
    // The charset narrowing must not reject real GitHub names.
    assertEquals(
      IssueHandle.parse("my-org/foo.github_io#7"),
      Right(IssueHandle("my-org", "foo.github_io", 7))
    )
    assertEquals(
      IssueHandle.parse("https://github.com/a/foo.github.io/issues/7"),
      Right(IssueHandle("a", "foo.github.io", 7))
    )

  test("parse rejects a host-prefixed short form"):
    // The short form must not swallow a URL-shaped input and produce an owner
    // of "github.com/acme".
    assert(IssueHandle.parse("github.com/acme/widgets#7").isLeft)

  test("parse rejects an issue number that does not fit in an Int"):
    assert(IssueHandle.parse("acme/widgets#99999999999999999999").isLeft)
    assert(
      IssueHandle
        .parse("https://github.com/acme/widgets/issues/99999999999999")
        .isLeft
    )

  test("parse rejects unrelated text"):
    assert(IssueHandle.parse("please fix the login bug").isLeft)

  test("parse rejects a URL without an issue number"):
    assert(IssueHandle.parse("https://github.com/acme/widgets/issues").isLeft)

  test("parse rejects a bare owner/repo with no number"):
    assert(IssueHandle.parse("acme/widgets").isLeft)

  test("the rejection message names both accepted forms"):
    val msg =
      IssueHandle.parse("nonsense").left.getOrElse(fail("expected Left"))
    assert(msg.contains("<owner>/<repo>#<number>"), msg)
    assert(msg.contains("github.com/<owner>/<repo>"), msg)

  test("parseOrThrow surfaces the message as an OrcaFlowException"):
    val e = intercept[orca.OrcaFlowException](IssueHandle.parseOrThrow("junk"))
    assert(e.getMessage.contains("junk"), e.getMessage)
