package orca.review

import orca.plan.Title

class ReviewFormattingTest extends munit.FunSuite:

  test("formatFinding renders the key, title, location, and suggestion"):
    val real = ReviewFinding(
      title = Title("Unbounded growth in `processBatch`"),
      description = "Unbounded growth in `processBatch`",
      location = Some(Location("src/main/Foo.scala", Some(42))),
      suggestion = Some("stream batches instead of buffering"),
      reopens = None
    )
    val rendered = formatFinding("I1.1", real)
    assert(
      rendered.startsWith("- I1.1 Unbounded growth in `processBatch`"),
      rendered
    )
    assert(
      rendered.contains("at src/main/Foo.scala:42"),
      s"missing location: $rendered"
    )
    assert(
      rendered.contains("suggestion: stream batches"),
      s"missing suggestion: $rendered"
    )

  test("formatReviewerOutcome bullets carry the agent's own key index"):
    // Keys name the agent that reported the finding, so the second agent's
    // findings are I2.n — that is what the fixer echoes back.
    val rendered = formatReviewerOutcome(
      "second",
      KeyedFinding.forAgent(1, List(finding("x"), finding("y")))
    )
    assert(rendered.contains("- I2.1 x"), rendered)
    assert(rendered.contains("- I2.2 y"), rendered)

  test("formatFinding renders a file-only location with no trailing line"):
    // A line without a file is unrepresentable (Location pairs them); this
    // pins the still-valid file-without-line case.
    val fileOnly = ReviewFinding(
      title = Title("Nit"),
      description = "Nit",
      location = Some(Location("src/main/Foo.scala", None)),
      suggestion = None,
      reopens = None
    )
    val rendered = formatFinding("I1.1", fileOnly)
    assert(
      rendered.contains("at src/main/Foo.scala") &&
        !rendered.contains("src/main/Foo.scala:"),
      s"expected a file-only location with no line; got: $rendered"
    )
