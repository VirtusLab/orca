package orca

import java.nio.charset.StandardCharsets.UTF_8

/** How the commit-message payload is shaped and bounded. The end-to-end wiring
  * — which git reads feed it, and when the model is called at all — is
  * [[CommitMessageTest]].
  */
class CommitDiffTest extends munit.FunSuite:

  private val stat =
    " seed.txt | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)"

  test("a diff line's leading margin character survives verbatim"):
    // A context line is `" " + source`, so any `stripMargin` block or markdown
    // table in the change produces lines the assembly must not rewrite.
    val payload =
      CommitDiff.payload(stat, "+first\n |context with pipe\n-removed")
    assert(payload.contains(" |context with pipe"), payload)

  test("an oversized diff is cut to the budget and marked as truncated"):
    val payload =
      CommitDiff.payload(stat, "+" + "x" * (CommitDiff.InlineThreshold * 2))
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
      val payload =
        CommitDiff.payload(stat, prefix + "🙂" * CommitDiff.InlineThreshold)
      assertEquals(String(payload.getBytes(UTF_8), UTF_8), payload)

  test("a stat too long for its share keeps the diff and the scope line"):
    val summary = " 2000 files changed, 2000 insertions(+), 2000 deletions(-)"
    val perFile =
      (1 to 2000).map(i => s" src/File$i.scala | 2 +-").mkString("\n")
    val payload = CommitDiff.payload(s"$perFile\n$summary", "+the real change")
    assert(payload.contains("+the real change"), "the stat starved the diff")
    assert(payload.contains(summary), "the stat's summary line was dropped")
