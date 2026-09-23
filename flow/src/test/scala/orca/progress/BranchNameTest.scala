package orca.progress

import munit.FunSuite

class BranchNameTest extends FunSuite:

  private def assertRefused(raw: String, rule: String)(using
      munit.Location
  ): Unit =
    BranchName.parse(raw) match
      case Left(msg) =>
        assert(msg.contains(rule), s"'$raw': '$msg' should name '$rule'")
        assert(msg.contains("pick another name"), msg)
      case Right(_) => fail(s"'$raw' must be refused")

  test("refuses an empty name"):
    assertRefused("", "is empty")

  test("refuses control characters and spaces, Unicode ones included"):
    assertRefused("a\tb", "control characters")
    assertRefused("a\u007fb", "control characters")
    assertRefused("a\u0085b", "control characters")
    assertRefused("a b", "spaces")
    assertRefused("a\u3000b", "spaces")

  test("refuses ~ ^ : ? * [ \\"):
    for c <- List('~', '^', ':', '?', '*', '[', '\\') do
      assertRefused(s"a${c}b", "must not contain any of")

  test("refuses '..'"):
    assertRefused("a..b", "'..'")

  test("refuses '@{' and a lone '@'"):
    assertRefused("a@{1}", "'@{'")
    assertRefused("@", "reserved name @")

  test("refuses a leading '-'"):
    assertRefused("-x", "start with '-'")

  test("refuses a component starting with '.' or ending with '.lock'"):
    assertRefused("a/.b", "starting with '.'")
    assertRefused("a.lock/b", "ending with '.lock'")

  test("refuses a leading, trailing or doubled '/'"):
    for raw <- List("/a", "a/", "a//b") do assertRefused(raw, "'/'")

  test("refuses a trailing '.'"):
    assertRefused("a.", "end with '.'")

  test("refuses HEAD"):
    assertRefused("HEAD", "reserved name HEAD")

  test("refuses the protected floor case-insensitively"):
    for raw <- List("main", "Master") do assertRefused(raw, "protected")

  test("accepts slash-separated and plain names"):
    for raw <- List(
        "feature/JIRA-123",
        "fix-42",
        "release/v1.2",
        "user@fix",
        "main-fix"
      )
    do assertEquals(BranchName.parse(raw).map(_.value), Right(raw))
