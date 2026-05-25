package orca.tools.codex

import CodexConversation.{StderrMaxBytes, StderrMaxLines, appendBounded}

/** Cap-edge coverage for [[CodexConversation.appendBounded]]. The function is
  * the only stateful piece of the stderr-buffering path that isn't trivially
  * verified by integration tests, and getting either cap wrong silently
  * truncates the failure diagnostics it's supposed to preserve.
  */
class AppendBoundedTest extends munit.FunSuite:

  test("evicts oldest lines when the line cap is exceeded"):
    val pre = (1 to StderrMaxLines).map(i => s"line $i").toVector
    val after = appendBounded(pre, "newest")
    assertEquals(after.size, StderrMaxLines)
    assertEquals(after.head, "line 2")
    assertEquals(after.last, "newest")

  test("evicts oldest lines when the byte cap is exceeded"):
    // Each line is ~half the byte budget; two of them plus a newcomer
    // would blow the cap, forcing the oldest out.
    val big = "x" * (StderrMaxBytes / 2 + 10)
    val seed = Vector(big, big)
    val after = appendBounded(seed, "newest")
    // The two `big` strings together already exceed StderrMaxBytes; the
    // oldest must have been dropped.
    assertEquals(after.size, 2)
    assertEquals(after.last, "newest")
    assertEquals(after.head, big)

  test("keeps at least one line even if it alone exceeds the byte cap"):
    // Empty diagnostics are useless; an oversized line is at least
    // *some* signal. The guard `result.size > 1` enforces this.
    val oversized = "y" * (StderrMaxBytes * 2)
    val after = appendBounded(Vector.empty, oversized)
    assertEquals(after, Vector(oversized))
