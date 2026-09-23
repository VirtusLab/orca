package orca.shell.sessions

import orca.AttemptId

import java.time.Instant

class SessionRefTest extends munit.FunSuite:

  test("parse reads back a ref's spelling"):
    val ref =
      SessionRef(AttemptId(Instant.parse("2026-07-18T09:00:00Z"), 42), 3)
    assertEquals(SessionRef.parse(ref.spelling), Some(ref))

  test("parse rejects a bare number"):
    assertEquals(SessionRef.parse("3"), None)

  test("parse rejects position 0"):
    assertEquals(SessionRef.parse("1752829200000-42:0"), None)
