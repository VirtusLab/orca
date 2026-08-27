package orca

import orca.tools.{ChangedFile, FileChange, PendingChanges}

import java.nio.charset.StandardCharsets.UTF_8

/** How each payload is shaped and bounded. The end-to-end wiring — which git
  * reads feed the commit payload, and when the model is called at all — is
  * [[CommitMessageTest]]; the review payload's is
  * `orca.review.ReviewChangeSetTest`.
  */
class BoundedDiffTest extends munit.FunSuite:

  private val stat =
    " seed.txt | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)"

  private def payloadOf(diff: String, newFiles: List[String] = Nil): String =
    BoundedDiff.commitPayload(PendingChanges(stat, newFiles, diff))

  test("a diff line's leading margin character survives verbatim"):
    // A context line is `" " + source`, so any `stripMargin` block or markdown
    // table in the change produces lines the assembly must not rewrite.
    val payload = payloadOf("+first\n |context with pipe\n-removed")
    assert(payload.contains(" |context with pipe"), payload)

  test("an oversized diff is cut to the budget and marked as truncated"):
    val payload = payloadOf("+" + "x" * (BoundedDiff.CommitThreshold * 2))
    assert(
      clue(payload.length) <= BoundedDiff.CommitThreshold,
      "the payload outgrew its budget"
    )
    assert(payload.endsWith("…(truncated)"), "the cut went unmarked")

  test("the cut never splits a surrogate pair"):
    // Both parities, since which one lands mid-pair depends on the stat's
    // length. A lone high surrogate isn't encodable — UTF-8 round-tripping
    // replaces it, which is what this asserts.
    for prefix <- List("+", "++") do
      val payload = payloadOf(prefix + "🙂" * BoundedDiff.CommitThreshold)
      assertEquals(String(payload.getBytes(UTF_8), UTF_8), payload)

  test("a stat too long for its share keeps the diff and the scope line"):
    val summary = " 2000 files changed, 2000 insertions(+), 2000 deletions(-)"
    val perFile =
      (1 to 2000).map(i => s" src/File$i.scala | 2 +-").mkString("\n")
    val payload = BoundedDiff.commitPayload(
      PendingChanges(s"$perFile\n$summary", Nil, "+the real change")
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
      clue(payload.length) <= BoundedDiff.CommitThreshold,
      "the payload outgrew its budget"
    )

  test("a truncated new-file list is cut between paths, never inside one"):
    val newFiles = (1 to 2000).map(i => s"src/generated/New$i.scala").toList
    val listed = payloadOf("+change", newFiles = newFiles).linesIterator
      .dropWhile(_ != "New files:")
      .drop(1)
      .takeWhile(_.startsWith("src/"))
      .toList
    assert(listed.nonEmpty && listed.forall(newFiles.contains), listed.last)

  test("a stage that only adds files has no empty stat section"):
    val payload =
      BoundedDiff.commitPayload(PendingChanges("", List("new.txt"), "+content"))
    assert(!payload.contains("Files changed:"), payload)

  test("nothing to describe yields an empty payload"):
    // The caller's cue to skip the model rather than ask it about no change.
    assertEquals(BoundedDiff.commitPayload(PendingChanges("", Nil, "")), "")

  // --- the review payload ---

  /** One file's section of a unified diff, as git renders it. */
  private def section(path: String, lines: Int): String =
    s"diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n" +
      s"@@ -0,0 +1,$lines @@\n" +
      (1 to lines).map(i => s"+a line of source, number $i").mkString("\n") +
      "\n"

  /** A change set of `count` equally-sized files, well past the threshold. */
  private def bigChangeSet(count: Int): (String, List[ChangedFile]) =
    val paths = (1 to count).map(i => f"src/File$i%03d.scala").toList
    val diff = paths.map(section(_, 300)).mkString
    (diff, paths.map(ChangedFile(_, FileChange.Lines(300, 0))))

  /** The paths whose own section the payload carries. */
  private def rendered(payload: String): List[String] =
    payload.linesIterator
      .filter(_.startsWith("diff --git "))
      .map(_.split(" b/").last)
      .toList

  /** The paths the payload's trailer names as not shown. */
  private def listed(payload: String): List[String] =
    payload.linesIterator
      .filter(_.startsWith("#   src/"))
      .map(_.drop(4).takeWhile(_ != ' '))
      .toList

  test("a review diff within the threshold is sent as it is"):
    val diff = section("src/Small.scala", 10)
    assertEquals(BoundedDiff.reviewPayload(diff, Nil), diff)

  test("what the trailer names and what the diff shows cover the change set"):
    // The point of the cap: a reviewer can always tell what it was not shown.
    val (diff, changed) = bigChangeSet(60)
    val payload = BoundedDiff.reviewPayload(diff, changed)
    assertEquals(
      (rendered(payload) ++ listed(payload)).sorted,
      changed.map(_.path).sorted
    )
    assertEquals(
      rendered(payload).toSet.intersect(listed(payload).toSet),
      Set.empty[String],
      "a file was both shown and named as not shown"
    )

  test("every file the cut diff shows is shown whole"):
    // A reviewer that judges half a file reports findings the rest answers.
    val (diff, changed) = bigChangeSet(60)
    val payload = BoundedDiff.reviewPayload(diff, changed)
    val shown = rendered(payload)
    assert(shown.nonEmpty, "nothing was rendered at all")
    assert(shown.forall(p => payload.contains(section(p, 300))), shown.last)

  test("a single file past the threshold renders no diff and is named"):
    val path = "src/Huge.scala"
    val diff = section(path, 6000)
    assert(clue(diff.length) > BoundedDiff.ReviewThreshold, "fixture too small")
    val payload =
      BoundedDiff.reviewPayload(
        diff,
        List(ChangedFile(path, FileChange.Lines(6000, 0)))
      )
    assertEquals(rendered(payload), Nil)
    assert(payload.contains(s"#   $path (+6000 -0)"), payload)

  test("the trailer says what a binary change and a new file are"):
    // Neither has a line count to give: git never counts a binary file, and an
    // untracked one has no tracked history to count against.
    val (diff, changed) = bigChangeSet(60)
    val payload = BoundedDiff.reviewPayload(
      diff,
      changed ++ List(
        ChangedFile("logo.png", FileChange.Binary),
        ChangedFile("notes.md", FileChange.New)
      )
    )
    assert(payload.contains("#   logo.png (binary)"), "binary went unlabelled")
    assert(
      payload.contains("#   notes.md (new file)"),
      "new file went unlabelled"
    )

  test("the trailer never renders a change as `+0 -0`"):
    // "+0 -0" reads as nothing having changed, which is never why a file is in
    // a change set.
    val (diff, changed) = bigChangeSet(60)
    val payload = BoundedDiff.reviewPayload(
      diff,
      changed :+ ChangedFile("script.sh", FileChange.Lines(0, 0))
    )
    assert(payload.contains("#   script.sh (no lines changed)"), payload)

  /** A path of exactly `chars` characters, nested the way a real one is. */
  private def deepPath(chars: Int): String =
    val leaf = "/Deep.scala"
    "nested/".repeat(chars / 7 + 1).take(chars - leaf.length) + leaf

  test("the payload stays in budget however many files the trailer names"):
    // Both halves have to be sized against each other: a long file list can
    // outweigh the diff, and neither may be allowed to spend the other's room.
    //
    // The trailer's room has to cover every subset of the file list, since
    // which files it names isn't known until the diff has been cut. Rendering
    // the whole list is not that bound: `boundedEntries` stops at the first
    // entry too long to fit, so the one long path here cuts the whole list
    // short of the budget. Showing the files ahead of it frees that room, and
    // the shorter list then renders longer than the whole one did. The sizes
    // are picked so the difference is a few file sections wide — reserving by
    // the whole list overshoots the threshold by ~3 KB.
    val inDiff = (1 to 1600).map(i => f"src/generated/G$i%05d.scala").toList
    val notInDiff = (1 to 900).map(i => f"src/untouched/U$i%05d.scala").toList
    val diff = inDiff.map(section(_, 2)).mkString
    assert(clue(diff.length) > BoundedDiff.ReviewThreshold, "fixture too small")
    val payload = BoundedDiff.reviewPayload(
      diff,
      (inDiff ++ (deepPath(3228) :: notInDiff))
        .map(ChangedFile(_, FileChange.Lines(2, 0)))
    )
    assert(
      clue(payload.length) <= BoundedDiff.ReviewThreshold,
      "the payload outgrew its budget"
    )

  test("the trailer reports how much diff the reviewer actually got"):
    // Not the threshold: the head is cut to leave the trailer its room.
    val (diff, changed) = bigChangeSet(60)
    val payload = BoundedDiff.reviewPayload(diff, changed)
    val shownChars = payload.indexOf("\n# The diff above was cut short at ")
    assert(shownChars > 0, payload)
    assert(payload.contains(s"cut short at $shownChars characters"), payload)

  test("a shown path containing ` b/` doesn't mark another file as shown"):
    // Git writes `diff --git a/x b/y.txt b/x b/y.txt` for a path with a space,
    // which ends with ` b/y.txt` — a suffix match would read the omitted
    // `y.txt` as shown and leave it out of the trailer, the one direction the
    // trailer exists to prevent.
    val (diff, changed) = bigChangeSet(60)
    val payload = BoundedDiff.reviewPayload(
      section("x b/y.txt", 2) + diff,
      ChangedFile("x b/y.txt", FileChange.Lines(2, 0)) ::
        ChangedFile("y.txt", FileChange.Lines(1, 0)) :: changed
    )
    assert(payload.contains("#   y.txt (+1 -0)"), payload)
    assert(
      !payload.contains("#   x b/y.txt "),
      s"the file that WAS rendered must stay out of the trailer: $payload"
    )

  // --- the sections payload ---

  /** The paths a sections payload's trailer names as not shown. */
  private def unshown(payload: String): List[String] =
    payload.linesIterator
      .filter(_.startsWith("#   "))
      .map(_.drop(4))
      .toList

  /** The payload of a cut that rendered something, failing the test when it
    * rendered nothing.
    */
  private def cut(
      diff: String,
      paths: List[String],
      maxChars: Int = 8 * 1024
  ): String =
    BoundedDiff.sectionsPayload(diff, paths, maxChars) match
      case BoundedDiff.SectionsCut.Rendered(payload) => payload
      case BoundedDiff.SectionsCut.NothingFits =>
        fail(s"expected sections for $paths")

  test("the sections payload carries the requested files and nothing else"):
    val diff = section("src/A.scala", 3) + section("src/B.scala", 3)
    assertEquals(cut(diff, List("src/B.scala")), section("src/B.scala", 3))

  test("a requested path repeated in the list is rendered once"):
    val path = "src/A.scala"
    assertEquals(cut(section(path, 3), List(path, path)), section(path, 3))

  test("a requested file too large for the budget leaves nothing to send"):
    // A payload of nothing but a trailer would tell the reviewer that the
    // sections above describe the change, above no sections at all.
    val path = "src/Huge.scala"
    assertEquals(
      BoundedDiff.sectionsPayload(section(path, 2000), List(path), 8 * 1024),
      BoundedDiff.SectionsCut.NothingFits
    )

  test("a requested path with no section of its own is named as not shown"):
    // A rename, whose header names two paths, or a header git had to quote:
    // the caller's file list has the path, the diff body has no section under
    // it.
    val payload = cut(
      section("src/A.scala", 3),
      List("src/A.scala", "src/Renamed.scala")
    )
    assertEquals(rendered(payload), List("src/A.scala"))
    assertEquals(unshown(payload), List("src/Renamed.scala"))

  test("the sections payload stays within its budget"):
    // Same sizing argument as the review payload's: sections and trailer are
    // bounded against each other, so neither spends the other's room — and
    // both still render, which a budget cut to nothing would also satisfy.
    val paths = (1 to 400).map(i => f"src/generated/G$i%05d.scala").toList
    val payload = cut(paths.map(section(_, 20)).mkString, paths)
    assert(clue(payload.length) <= 8 * 1024, "the payload outgrew its budget")
    assert(rendered(payload).nonEmpty, "no section was rendered")
    assert(unshown(payload).nonEmpty, "no omitted file was named")

  // --- the PR payload ---

  test("a PR diff within the threshold is sent as it is"):
    val diff = section("src/Small.scala", 10)
    assertEquals(BoundedDiff.prPayload(diff), diff)

  test("a PR diff past the threshold is cut to its head and marked"):
    assertEquals(
      BoundedDiff.prPayload("+" * (BoundedDiff.ReviewThreshold * 2)),
      "+" * BoundedDiff.ReviewThreshold +
        s"\n\n[diff cut at ${BoundedDiff.ReviewThreshold} characters — " +
        "the summary covers the leading files only]"
    )

  test("the PR cut never splits a surrogate pair"):
    // The one-char prefix is what lands the cut mid-pair: the threshold is
    // even, so a bare run of two-char emoji would break between pairs. A lone
    // high surrogate isn't encodable — UTF-8 round-tripping replaces it, which
    // is what this asserts.
    val payload =
      BoundedDiff.prPayload("+" + "🙂" * BoundedDiff.ReviewThreshold)
    assertEquals(String(payload.getBytes(UTF_8), UTF_8), payload)
