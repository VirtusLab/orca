package orca.backend

import BufferedStderrDiagnostics.{MaxBytes, MaxLines, appendBounded}

/** Cap-edge coverage for [[BufferedStderrDiagnostics.appendBounded]]. The
  * function is the only stateful piece of the stderr-buffering path that isn't
  * trivially verified by integration tests, and getting either cap wrong
  * silently truncates the failure diagnostics it's supposed to preserve.
  */
class AppendBoundedTest extends munit.FunSuite:

  test("evicts oldest lines when the line cap is exceeded"):
    val pre = (1 to MaxLines).map(i => s"line $i").toVector
    val after = appendBounded(pre, "newest")
    assertEquals(after.size, MaxLines)
    assertEquals(after.head, "line 2")
    assertEquals(after.last, "newest")

  test("evicts oldest lines when the byte cap is exceeded"):
    // Each line is ~half the byte budget; two of them plus a newcomer
    // would blow the cap, forcing the oldest out.
    val big = "x" * (MaxBytes / 2 + 10)
    val after = appendBounded(Vector(big, big), "newest")
    // Single structural assertion: fails informatively if the byte loop
    // trims too much (e.g. down to just "newest") or too little.
    assertEquals(after, Vector(big, "newest"))

  test("keeps at least one line even if it alone exceeds the byte cap"):
    // Empty diagnostics are useless; an oversized line is at least
    // *some* signal. The guard `result.size > 1` enforces this.
    val oversized = "y" * (MaxBytes * 2)
    val after = appendBounded(Vector.empty, oversized)
    assertEquals(after, Vector(oversized))
