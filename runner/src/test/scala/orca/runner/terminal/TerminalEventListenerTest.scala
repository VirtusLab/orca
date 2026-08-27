package orca.runner.terminal

import orca.events.OrcaEvent
import orca.testkit.Usages.usage
import orca.agents.Model
import java.io.{ByteArrayOutputStream, PrintStream}

/** Drives the listener directly against a synchronous `TerminalOutputState`,
  * bypassing the actor so output is readable immediately rather than racing a
  * worker thread.
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
        OrcaEvent.Step("Issue summary\n  at src/Foo.scala:10"),
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

  test("a Caveat renders un-indented with `!`, whatever stage is open"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.Caveat("Codex cannot stop a NetworkOnly turn"),
        OrcaEvent.StageCompleted("plan")
      )
    )
    val line = output
      .split('\n')
      .find(_.contains("NetworkOnly"))
      .getOrElse(fail(s"missing caveat line; got: $output"))
    assertEquals(
      line,
      s"${TerminalEventListener.CaveatGlyph} Codex cannot stop a NetworkOnly turn"
    )

  test("a Caveat's glyph is painted in the caveat style, its body left plain"):
    val output = renderWith(
      animated = false,
      events = List(OrcaEvent.Caveat("no gate here")),
      listenerUseColor = true
    )
    val glyph = TerminalEventListener
      .CaveatStyle(s"${TerminalEventListener.CaveatGlyph} ")
      .render
    val line = output.split('\n').head
    assert(line.startsWith(glyph), line)
    assertEquals(line.stripPrefix(glyph), "no gate here")

  test("a run of read-only calls collapses to one line plus a repeat count"):
    // The count lands once a different line closes the run — here the Step.
    val output = renderEvents(
      List(
        OrcaEvent.ToolUse("Read", """{"file_path":"stats.py"}"""),
        OrcaEvent.ToolUse("Grep", """{"pattern":"variance"}"""),
        OrcaEvent.ToolUse("Glob", """{"pattern":"**/*.py"}"""),
        OrcaEvent.Step("done")
      )
    )
    assertEquals(
      output,
      s"⏺ read\n  ⎿ ×3\n${TerminalEventListener.StageStartGlyph} done\n"
    )

  test("read-only calls from several agents still collapse into one line"):
    // The reviewer fan-out interleaves emitters, so a `name: ` prefix on these
    // lines would make every one of them distinct and collapse nothing —
    // exactly where reads are densest.
    val output = renderEvents(
      List(
        OrcaEvent
          .ToolUse("Read", """{"file_path":"stats.py"}""", Some("alpha")),
        OrcaEvent.ToolUse("Grep", """{"pattern":"variance"}""", Some("beta")),
        OrcaEvent
          .ToolUse("Read", """{"file_path":"other.py"}""", Some("alpha")),
        OrcaEvent.Step("done")
      )
    )
    assertEquals(
      output,
      s"⏺ read\n  ⎿ ×3\n${TerminalEventListener.StageStartGlyph} done\n"
    )

  test("an agent that only read is still counted as an emitter"):
    // Its own lines went out bare, but the stage now has two emitters, so the
    // next line that CAN carry a name must carry one.
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("task"),
        OrcaEvent
          .ToolUse("Read", """{"file_path":"stats.py"}""", Some("alpha")),
        OrcaEvent.AssistantMessage("reviewed it", Some("beta"))
      )
    )
    assert(output.contains("● beta: reviewed it"), output)

  test("an unrecognised tool name is treated as mutating and printed in full"):
    // The classification is best-effort; a write shown as a read is the
    // harmful direction, so anything unknown keeps its name and arguments.
    val output = renderEvents(
      List(OrcaEvent.ToolUse("mcp__docs__search", """{"query":"variance"}"""))
    )
    assert(output.contains("⏺ mcp__docs__search (variance)"), output)

  test("orca's own bookkeeping stays out of the log"):
    // The second commit is an agent's, and orca is developed with orca — its
    // message reads exactly like the runtime's, so only the event tells them
    // apart.
    val output = renderEvents(
      List(
        OrcaEvent.Bookkeeping("Committed: orca: progress log"),
        OrcaEvent.Step("Committed: orca: fix the terminal renderer")
      )
    )
    assertEquals(
      output,
      s"${TerminalEventListener.StageStartGlyph} Committed: orca: fix the terminal renderer\n"
    )

  test("an error carrying an agent name is attributed to it"):
    // The stage error that follows carries no name, which is what tells the
    // agent's own failure apart from the stage's report of it.
    val output = renderEvents(
      List(
        OrcaEvent.Error("turn failed: rate limited", Some("readability")),
        OrcaEvent.Error("Stage 'review' failed: turn failed: rate limited")
      )
    )
    assertEquals(
      output,
      "✖ readability: turn failed: rate limited\n" +
        "✖ Stage 'review' failed: turn failed: rate limited\n"
    )

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
    // The cap bounds the whole rendered line, glyph included.
    val bodyLines = output.split('\n').filter(_.contains("x"))
    assert(
      bodyLines.forall(
        _.length <= TerminalEventListener.MaxAssistantMessageLength
      ),
      bodyLines.toList
    )

  test("an indented, agent-prefixed prose line still fits the cap"):
    // Depth 3 plus a `name: ` prefix is the worst case in a review fan-out:
    // indent, glyph and prefix all come out of the body's budget.
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("outer"),
        OrcaEvent.StageStarted("middle"),
        OrcaEvent.StageStarted("inner"),
        OrcaEvent.AssistantMessage("first", Some("main")),
        OrcaEvent.AssistantMessage(
          "y" * (TerminalEventListener.MaxAssistantMessageLength * 2),
          Some("readability")
        )
      )
    )
    val line = output
      .split('\n')
      .find(_.contains("readability:"))
      .getOrElse(fail(s"missing the attributed line; got: $output"))
    assertEquals(line.length, TerminalEventListener.MaxAssistantMessageLength)

  test("AssistantMessage with whitespace-only body emits nothing"):
    val output = renderEvents(List(OrcaEvent.AssistantMessage("   \n\t  ")))
    assertEquals(output, "")

  test("a stage's own agent is never named on its tool lines"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("task"),
        OrcaEvent
          .ToolUse("Write", """{"file_path":"stats.py"}""", Some("main")),
        OrcaEvent.ToolUse("Write", """{"file_path":"other.py"}""", Some("main"))
      )
    )
    assert(!output.contains("main:"), output)

  test("a second agent's tool line is prefixed with its name"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("task"),
        OrcaEvent
          .ToolUse("Write", """{"file_path":"stats.py"}""", Some("main")),
        OrcaEvent
          .ToolUse("Write", """{"file_path":"stats.py"}""", Some("readability"))
      )
    )
    assert(output.contains("⏺ readability: Write (stats.py)"), output)

  test("a second agent's prose line is prefixed with its name"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("task"),
        OrcaEvent.AssistantMessage("planning", Some("main")),
        OrcaEvent.AssistantMessage("reviewing", Some("test"))
      )
    )
    assert(output.contains("● test: reviewing"), output)

  test("the first agent is named too once a second one has emitted"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("task"),
        OrcaEvent.AssistantMessage("planning", Some("main")),
        OrcaEvent.AssistantMessage("reviewing", Some("test")),
        OrcaEvent.AssistantMessage("still planning", Some("main"))
      )
    )
    assert(output.contains("● main: still planning"), output)

  test("a new stage lets its own first agent go unnamed again"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("outer"),
        OrcaEvent.AssistantMessage("planning", Some("main")),
        OrcaEvent.StageStarted("inner"),
        OrcaEvent.AssistantMessage("reviewing", Some("test"))
      )
    )
    assert(!output.contains("test:"), output)

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
          usage(10L, 5L),
          cost = None
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
    // A regression guard for the single-writer / @volatile publication contract,
    // NOT a race proof: the sole writer pushes/pops 500 pairs while a reader
    // polls `currentIndent` (the same lock-free access ConversationRenderer makes
    // mid-readLine). Asserts the reader never crashes and the stack unwinds to
    // empty once every pair is balanced.
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
