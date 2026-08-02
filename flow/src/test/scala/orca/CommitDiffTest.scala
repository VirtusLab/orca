package orca

import java.nio.charset.StandardCharsets.UTF_8

/** How the commit-message payload is shaped and bounded. The end-to-end wiring
  * — which git reads feed it, and when the model is called at all — is
  * [[CommitMessageTest]].
  */
class CommitDiffTest extends munit.FunSuite:

  private val stat =
    " seed.txt | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)"

  private def payloadOf(diff: String, newFiles: List[String] = Nil): String =
    CommitDiff.payload(stat = stat, newFiles = newFiles, diff = diff)

  test("a diff line's leading margin character survives verbatim"):
    // A context line is `" " + source`, so any `stripMargin` block or markdown
    // table in the change produces lines the assembly must not rewrite.
    val payload = payloadOf("+first\n |context with pipe\n-removed")
    assert(payload.contains(" |context with pipe"), payload)

  test("an oversized diff is cut to the budget and marked as truncated"):
    val payload = payloadOf("+" + "x" * (CommitDiff.InlineThreshold * 2))
    assert(
      clue(payload.length) <= CommitDiff.InlineThreshold + 64,
      "the payload outgrew its budget"
    )
    assert(payload.endsWith("…(truncated)"), "the cut went unmarked")

  test("the cut never splits a surrogate pair"):
    // Both parities, since which one lands mid-pair depends on the stat's
    // length. A lone high surrogate isn't encodable: UTF-8 round-tripping
    // replaces it, and the JSON writer that puts the prompt on the wire throws.
    for prefix <- List("+", "++") do
      val payload = payloadOf(prefix + "🙂" * CommitDiff.InlineThreshold)
      assertEquals(String(payload.getBytes(UTF_8), UTF_8), payload)

  test("a stat too long for its share keeps the diff and the scope line"):
    val summary = " 2000 files changed, 2000 insertions(+), 2000 deletions(-)"
    val perFile =
      (1 to 2000).map(i => s" src/File$i.scala | 2 +-").mkString("\n")
    val payload = CommitDiff.payload(
      stat = s"$perFile\n$summary",
      newFiles = Nil,
      diff = "+the real change"
    )
    assert(payload.contains("+the real change"), "the stat starved the diff")
    assert(payload.contains(summary), "the stat's summary line was dropped")

  test("new files are listed in their own section"):
    val payload = payloadOf("+content", newFiles = List("src/New.scala"))
    assert(payload.contains("New files:\nsrc/New.scala"), payload)

  test("a long new-file list starves neither the stat nor the diff"):
    val newFiles = (1 to 2000).map(i => s"src/generated/New$i.scala").toList
    val payload = payloadOf("+the real change", newFiles = newFiles)
    assert(payload.contains("+the real change"), "the list starved the diff")
    assert(payload.contains(" seed.txt | 2 +-"), "the list starved the stat")
    assert(
      clue(payload.length) <= CommitDiff.InlineThreshold + 64,
      "the payload outgrew its budget"
    )

  test("nothing to describe yields an empty payload"):
    // The caller's cue to skip the model rather than ask it about no change.
    assertEquals(CommitDiff.payload(stat = "", newFiles = Nil, diff = ""), "")
