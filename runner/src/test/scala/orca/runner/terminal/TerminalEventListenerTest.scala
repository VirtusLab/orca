package orca.runner.terminal

import orca.events.{OrcaEvent, Usage}
import orca.agents.Model
import java.io.{ByteArrayOutputStream, PrintStream}

/** These tests exercise the listener's synchronous state-mutation behaviour by
  * driving it directly with a synchronous `TerminalOutputState` — bypassing the
  * actor so the test reads output immediately rather than racing a worker
  * thread.
  */
class TerminalEventListenerTest extends munit.FunSuite:

  private def renderEvents(events: List[OrcaEvent]): String =
    renderWith(animated = false, events)

  private def renderWith(
      animated: Boolean,
      events: List[OrcaEvent],
      listenerUseColor: Boolean = false
  ): String =
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    val output =
      new TerminalOutputState(ps, useColor = false, animated = animated)
    val listener =
      new TerminalEventListener(output, useColor = listenerUseColor)
    events.foreach(listener.onEvent)
    buf.toString

  test("StageStarted prints a ▶ line; StageCompleted is silent in the log"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.StageCompleted("plan")
      )
    )
    assert(output.contains("plan"))
    assert(output.contains(TerminalEventListener.StageStartGlyph))
    assert(
      !output.contains(TerminalEventListener.StageDoneGlyph),
      s"StageCompleted must not render to the event log; got: $output"
    )

  test(
    "multi-line Step body re-indents continuation lines under the stage depth"
  ):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("review"),
        OrcaEvent.Step("[Warning] Issue summary\n  at src/Foo.scala:10"),
        OrcaEvent.StageCompleted("review")
      )
    )
    val lines = output.split('\n').toList
    assert(
      lines.exists(_.contains("Issue summary")),
      s"missing Step header line; got: $lines"
    )
    val locationLine = lines
      .find(_.contains("at src/Foo.scala:10"))
      .getOrElse(fail(s"missing location line; got: $lines"))
    // At depth 1 the indent is 2 spaces. The Step header has those 2
    // spaces plus the glyph; the location line also gets the 2-space
    // depth indent (so it sits at col 2 of the rendered line, plus
    // its own internal "  " hanging indent = col 4).
    assert(
      locationLine.startsWith("    "),
      s"continuation line should be re-indented under the stage; got: '$locationLine'"
    )

  test("Step events render as a single ▶ line, no closing ✔"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("outer"),
        OrcaEvent.Step("Switched to a new branch 'foo'"),
        OrcaEvent.StageCompleted("outer")
      )
    )
    assert(output.contains("Switched to a new branch 'foo'"))
    assert(
      output.contains(s"${TerminalEventListener.StageStartGlyph} Switched"),
      s"Step should render with the ▶ glyph; got: $output"
    )
    assert(
      !output.contains(s"${TerminalEventListener.StageDoneGlyph} Switched"),
      s"Step events must never produce a closing ✔ line; got: $output"
    )

  test("errors are prefixed with an error marker"):
    val output = renderEvents(List(OrcaEvent.Error("boom")))
    assert(output.contains(TerminalEventListener.ErrorGlyph))
    assert(output.contains("boom"))

  test("errors with backend terminal controls do not crash colored output"):
    val output = renderWith(
      animated = false,
      events = List(
        OrcaEvent.Error(
          "before\u001b[?25lhidden\u001b[2Kafter" +
            "\u001b]8;;https://example.com\u0007link\u001b]8;;\u0007"
        )
      ),
      listenerUseColor = true
    )
    assert(output.contains("beforehiddenafterlink"), output)
    assert(!output.contains("?25l"), output)
    assert(!output.contains("[2K"), output)
    assert(!output.contains("https://example.com"), output)

  test("AssistantMessage renders as a `●` line with the body"):
    val output =
      renderEvents(List(OrcaEvent.AssistantMessage("hello there")))
    assert(output.contains(TerminalEventListener.AssistantGlyph))
    assert(output.contains("hello there"))

  test("AssistantMessage collapses multi-line bodies to one line"):
    val output = renderEvents(
      List(OrcaEvent.AssistantMessage("line one\nline two\nline three"))
    )
    val rendered = output.split('\n').filter(_.contains("line")).mkString
    assert(rendered.contains("line one line two line three"), rendered)

  test("AssistantMessage truncates long bodies with an ellipsis"):
    val long = "x" * (TerminalEventListener.MaxAssistantMessageLength + 50)
    val output = renderEvents(List(OrcaEvent.AssistantMessage(long)))
    assert(output.contains("…"), output)
    // Truncated form is bounded by the cap (plus glyph + indent).
    val bodyLines = output.split('\n').filter(_.contains("x"))
    assert(
      bodyLines.forall(
        _.length <= TerminalEventListener.MaxAssistantMessageLength + 10
      ),
      bodyLines.toList
    )

  test("AssistantMessage with whitespace-only body emits nothing"):
    val output = renderEvents(List(OrcaEvent.AssistantMessage("   \n\t  ")))
    assertEquals(output, "")

  test("UserPrompt renders as a `▸` line with the one-line collapsed body"):
    val output =
      renderEvents(
        List(OrcaEvent.UserPrompt("Add a multiply function\nwith tests"))
      )
    assert(output.contains(TerminalEventListener.UserPromptGlyph), output)
    assert(output.contains("Add a multiply function with tests"), output)

  test("UserPrompt with whitespace-only body emits nothing"):
    val output = renderEvents(List(OrcaEvent.UserPrompt("   \n\t  ")))
    assertEquals(output, "")

  test(
    "StructuredResult without a summary renders the collapsed raw payload as a `●` line"
  ):
    val output = renderEvents(
      List(OrcaEvent.StructuredResult("""{"answer":42}""", None))
    )
    assert(output.contains(TerminalEventListener.AssistantGlyph), output)
    assert(output.contains("""{"answer":42}"""), output)

  test(
    "StructuredResult with a summary renders the summary as a `▶` line, not the raw payload"
  ):
    val output = renderEvents(
      List(
        OrcaEvent.StructuredResult(
          """{"answer":42}""",
          Some("Answer: 42")
        )
      )
    )
    assert(output.contains(TerminalEventListener.StageStartGlyph), output)
    assert(output.contains("Answer: 42"), output)
    assert(!output.contains("""{"answer":42}"""), output)

  test(
    "StructuredResult with a deliberately-silent summary (Some(\"\")) renders nothing"
  ):
    // A specific Announce[O] that says nothing (e.g. ReviewResult — the review
    // loop narrates per-reviewer outcomes itself) must not trigger the raw
    // fallback: that would render the JSON the summary deliberately withheld.
    val output = renderEvents(
      List(OrcaEvent.StructuredResult("""{"issues":[]}""", Some("")))
    )
    assertEquals(output, "")

  test(
    "StructuredResult raw fallback truncates long payloads with an ellipsis"
  ):
    val long = "{\"x\":\"" + ("a" * 300) + "\"}"
    val output = renderEvents(List(OrcaEvent.StructuredResult(long, None)))
    assert(output.contains("…"), output)
    val bodyLines = output.split('\n').filter(_.contains("a"))
    assert(
      bodyLines.forall(
        _.length <= TerminalEventListener.MaxStructuredResultRawLength + 10
      ),
      bodyLines.toList
    )

  test(
    "StructuredResult raw fallback collapses multi-line payloads to one line"
  ):
    val raw = "{\n  \"a\": 1,\n  \"b\": 2\n}"
    val output = renderEvents(List(OrcaEvent.StructuredResult(raw, None)))
    val rendered = output.split('\n').filter(_.contains("\"a\"")).mkString
    assert(rendered.contains("""{ "a": 1, "b": 2 }"""), rendered)

  test("TokensUsed events are ignored (owned by CostTracker)"):
    val output = renderEvents(
      List(
        OrcaEvent.TokensUsed(
          "claude",
          Some(Model("opus")),
          Usage(10L, 5L, None)
        )
      )
    )
    assertEquals(output, "")

  test(
    "status bar shows only the innermost stage (no breadcrumb concatenation)"
  ):
    val rendered = renderWith(
      animated = true,
      List(
        OrcaEvent.StageStarted(
          "Implement task: very long task title that would dominate"
        ),
        OrcaEvent.StageStarted("Implementation")
      )
    )
    // Find the most recent status redraw — the bytes after the last
    // ClearLine escape (`\r[2K`). Both names land in the event log via
    // the `▶` lines, but the status bar should only pin the innermost.
    val tail = rendered.split("\\[2K").last
    assert(
      tail.contains("Implementation"),
      s"status bar should pin the innermost stage; tail was: '$tail'"
    )
    assert(
      !tail.contains("very long task title"),
      s"outer stage title leaked into the status bar; tail was: '$tail'"
    )

  test("nested stages indent inner content; no ✔ ever appears in the log"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("outer"),
        OrcaEvent.StageStarted("inner"),
        OrcaEvent.Error("inside inner"),
        OrcaEvent.StageCompleted("inner"),
        OrcaEvent.StageCompleted("outer")
      )
    )
    val lines = output.split('\n').toList
    val outerStartLine = lines
      .find(l =>
        l.contains("outer") && l.contains(TerminalEventListener.StageStartGlyph)
      )
      .getOrElse(fail("outer start line missing"))
    val innerStartLine = lines
      .find(l =>
        l.contains("inner") && l.contains(TerminalEventListener.StageStartGlyph)
      )
      .getOrElse(fail("inner start line missing"))
    val errorLine = lines
      .find(_.contains("inside inner"))
      .getOrElse(fail("error line missing"))
    assert(
      !outerStartLine.startsWith(" "),
      s"outer marker should be flush left: '$outerStartLine'"
    )
    assert(
      innerStartLine.startsWith("  ") && !innerStartLine.startsWith("    "),
      s"inner marker indented by 2: '$innerStartLine'"
    )
    assert(
      errorLine.startsWith("    "),
      s"inner content indented by 4 (2 levels × 2 spaces): '$errorLine'"
    )
    assert(
      !output.contains(TerminalEventListener.StageDoneGlyph),
      s"no ✔ should appear in the event log; got: $output"
    )

  test("currentIndent stays readable while stages push and pop concurrently"):
    // A light regression guard for the single-writer / @volatile publication
    // contract, NOT a race proof: the test thread is the sole writer (pushing
    // then popping 500 StageStarted/StageCompleted pairs) while a reader thread
    // polls `currentIndent` from another thread — the same lock-free access the
    // ConversationRenderer makes mid-readLine. It asserts the reader never
    // crashes and that the stack unwinds to empty once every pair is balanced.
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    val output =
      new TerminalOutputState(ps, useColor = false, animated = false)
    val listener = new TerminalEventListener(output, useColor = false)

    @volatile var readerFailure: Option[Throwable] = None
    @volatile var stop = false
    val reader = new Thread(() =>
      try
        while !stop do
          val _ = listener.currentIndent.length
      catch case t: Throwable => readerFailure = Some(t)
    )
    reader.start()
    for _ <- 1 to 500 do
      listener.onEvent(OrcaEvent.StageStarted("s"))
      listener.onEvent(OrcaEvent.StageCompleted("s"))
    stop = true
    reader.join(5000)

    assertEquals(readerFailure, None, s"reader thread failed: $readerFailure")
    assertEquals(
      listener.currentIndent,
      "",
      "balanced push/pop pairs must unwind the indent stack to empty"
    )
