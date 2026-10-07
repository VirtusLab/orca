package orca.review.diff

import orca.review.diff.DiffDeliveryTest.diffSection

/** What a resumed reviewer is sent when the change set has grown past what a
  * re-review prompt inlines, and what it is recorded as holding.
  */
class DiffDeliveryTest extends munit.FunSuite:

  /** A sample of these per-file sections, in order, after `preamble`. */
  private def sampleOf(
      sections: List[(String, String)],
      preamble: String = ""
  ): DiffSample =
    DiffSample(
      preamble + sections.map(_._2).mkString,
      sections.map(_._1),
      sections.toMap
    )

  /** What a resumed reviewer holding `previous` is sent about `current`. */
  private def reReviewOf(
      previous: LastSent,
      current: DiffSample
  ): ReReviewChanges =
    DiffDelivery.next(previous, current).message match
      case DiffMessage.ReReview(changes) => changes
      case other => fail(s"expected a re-review message, got $other")

  test("a too-large re-sample sends only the sections that changed"):
    // Under a whole-run diff the delta since a reviewer's last look is
    // typically one fix, so that is what it is sent — not the run's whole file
    // list to re-read, and not the whole diff again. What it holds afterwards
    // is the whole sample, so a later rewrite of the same files still
    // registers.
    val unchangedFile = "a.scala" -> diffSection("a.scala", "one", 4000)
    val previous = LastSent.Inline(
      sampleOf(List(unchangedFile, "b.scala" -> diffSection("b.scala", "two")))
    )
    val current = sampleOf(
      List(unchangedFile, "b.scala" -> diffSection("b.scala", "three"))
    )
    val delivery = DiffDelivery.next(previous, current)
    delivery.message match
      case DiffMessage.ReReview(
            ReReviewChanges.Sections(sections, changed, unchanged)
          ) =>
        assertEquals(changed, List("b.scala"))
        assertEquals(unchanged, List("a.scala"))
        assert(sections.contains("+three"), sections)
        assert(!sections.contains("+one"), sections)
      case other => fail(s"expected bounded sections, got $other")
    assertEquals(delivery.lastSent, LastSent.SectionsOnly(current))

  test("a too-large re-sample with no previous diff names every path"):
    // Nothing to compare against — the reviewer's last round could sample
    // nothing — so every path counts as changed, marked as such by the empty
    // unchanged list.
    val current = sampleOf(
      List(
        "a.scala" -> diffSection("a.scala", "one", 1200),
        "b.scala" -> diffSection("b.scala", "two", 1200)
      )
    )
    reReviewOf(LastSent.NoteOnly(DiffSample.empty), current) match
      case ReReviewChanges.Sections(_, changed, unchanged) =>
        assertEquals(changed, List("a.scala", "b.scala"))
        assertEquals(unchanged, Nil)
      case other => fail(s"expected bounded sections, got $other")

  test("a too-large re-sample whose delta names no file has no sections"):
    // The samples differ outside every parseable section — here in the
    // preamble a cut sample carries — so there is nothing to cut sections
    // from, and the reviewer is pointed at the files instead.
    val sections = List("a.scala" -> diffSection("a.scala", "one", 4000))
    val previous =
      LastSent.Inline(sampleOf(sections, preamble = "# skipped 1 file\n"))
    val current = sampleOf(sections, preamble = "# skipped 2 files\n")
    assertEquals(
      reReviewOf(previous, current),
      ReReviewChanges.Paths(List("a.scala"))
    )

  test("a re-sample whose text is unchanged but a section is not is re-sent"):
    // The text a reviewer is sent can be cut short of an edited file.
    val previous = LastSent.Inline(sampleOf(List("a.scala" -> "one")))
    val current = sampleOf(List("a.scala" -> "one")).copy(
      sections = Map("a.scala" -> "two")
    )
    assertEquals(
      reReviewOf(previous, current),
      ReReviewChanges.Updated(current.diff)
    )

object DiffDeliveryTest:
  /** A minimal per-file diff section, padded to `pad` filler lines so a sample
    * can be pushed past the inline threshold.
    */
  def diffSection(path: String, marker: String, pad: Int = 0): String =
    s"diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n" +
      s"@@ -1 +1 @@\n+$marker\n" + ("+filler\n" * pad)
