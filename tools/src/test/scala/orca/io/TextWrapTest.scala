package orca.io

class TextWrapTest extends munit.FunSuite:

  test("short input passes through unchanged"):
    assertEquals(TextWrap.wrap("hello world", maxWidth = 80), "hello world")

  test("wraps at the last whitespace boundary that fits"):
    val out = TextWrap.wrap(
      "the quick brown fox jumps over the lazy dog",
      maxWidth = 20
    )
    val lines = out.split('\n').toList
    // Continuation lines start with the default 2-space hanging
    // indent; every line stays within maxWidth.
    assert(
      lines.tail.forall(_.startsWith("  ")),
      s"continuation lines should be indented; got: $lines"
    )
    assert(
      lines.forall(_.length <= 20),
      s"every line ≤ maxWidth=20; got: $lines"
    )

  test("a token longer than maxWidth is emitted on its own line"):
    val out = TextWrap.wrap(
      "short verylongtokenthatcannotbebrokenup tail",
      maxWidth = 10
    )
    val lines = out.split('\n').toList
    assert(
      lines.exists(_.contains("verylongtokenthatcannotbebrokenup")),
      s"the long token should appear intact; got: $lines"
    )

  test("custom continuation prefix is used on wrap"):
    val out =
      TextWrap.wrap("alpha beta gamma delta", maxWidth = 8, continuation = "→ ")
    val lines = out.split('\n').toList
    assert(
      lines.tail.forall(_.startsWith("→ ")),
      s"every continuation line should start with the custom prefix; got: $lines"
    )

  test("preserves explicit \\n boundaries; each line wraps independently"):
    val out = TextWrap.wrap("line one wraps\nline two", maxWidth = 10)
    assert(
      out.contains("line two"),
      s"second logical line should appear; got: $out"
    )

  test("pure whitespace input collapses to empty"):
    assertEquals(TextWrap.wrap("   \t  ", maxWidth = 80), "")

  test("preserves leading whitespace on the first line"):
    assertEquals(
      TextWrap.wrap("  suggestion: keep this", maxWidth = 80),
      "  suggestion: keep this"
    )
