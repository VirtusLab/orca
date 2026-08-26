package orca.progress

import munit.FunSuite

class CommitHashTest extends FunSuite:

  test("from accepts a full hash"):
    val hash = "0badc0ffee0ddf00d1234567890abcdef1234567"
    assertEquals(CommitHash.from(hash).map(_.value), Some(hash))

  test("from accepts an abbreviation git can resolve"):
    assertEquals(CommitHash.from("0bad").map(_.value), Some("0bad"))

  test("from refuses an abbreviation too short to name one commit"):
    assertEquals(CommitHash.from("0ba"), None)
    assertEquals(CommitHash.from(""), None)

  test("from refuses anything that isn't hex"):
    assertEquals(CommitHash.from("--output=/etc/passwd"), None)
    assertEquals(CommitHash.from("HEAD~1"), None)
