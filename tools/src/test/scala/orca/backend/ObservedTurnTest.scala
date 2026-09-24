package orca.backend

import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.agents.{BackendTag, StructuredOutputMode, WireSessionId}
import orca.events.{OrcaEvent, OrcaListener, TurnDebit, Usage}
import orca.testkit.ScriptedTurn

import java.util.concurrent.atomic.AtomicInteger

/** A conversation whose event stream throws partway through iteration, standing
  * in for a subprocess that dies mid-turn: the scripted events are yielded
  * first, then the next `foreach` step raises `crash`. `awaitResult()` is never
  * reached because the drain's event loop throws before it.
  */
private class CrashingTurn(
    eventList: List[TurnEvent],
    crash: Throwable,
    override val outputSchema: Option[String] = None
) extends LiveTurn[BackendTag.Codex.type]:
  val cancelCount = new AtomicInteger(0)
  def events: Iterator[TurnEvent] =
    eventList.iterator ++ Iterator.continually[TurnEvent](throw crash)
  def awaitResult()
      : Either[OrcaInteractiveCancelled, AgentResult[BackendTag.Codex.type]] =
    throw new IllegalStateException("awaitResult should be unreachable")
  def canAskUser: Boolean = false
  def cancel(): Unit =
    val _ = cancelCount.incrementAndGet()

class ObservedTurnTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    wireId = WireSessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage.empty
  )

  private val approveBash = TurnEvent.ApproveTool(
    "Bash",
    """{"command":"ls"}""",
    _ => ()
  )

  test("a cancelled turn throws its OrcaInteractiveCancelled"):
    val cancelled = new OrcaInteractiveCancelled(TurnDebit.Unobserved)
    val live = new ScriptedTurn(Nil, Left(cancelled))
    val thrown = intercept[OrcaInteractiveCancelled]:
      ObservedTurn(live, OrcaListener.noop).drain(_ => ())
    assertEquals(thrown, cancelled)

  test("the opening UserMessage surfaces as OrcaEvent.UserPrompt"):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(TurnEvent.UserMessage("fix the build")),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(recorder.events, List(OrcaEvent.UserPrompt("fix the build")))

  test("a tool call reaches the listener before the next event is answered"):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantToolCall("Read", """{"file":"a"}"""),
        approveBash
      ),
      Right(sampleResult)
    )
    var seenAtAnswer: List[OrcaEvent] = Nil
    val _ = ObservedTurn(live, recorder).drain: _ =>
      seenAtAnswer = recorder.events
    assertEquals(
      seenAtAnswer,
      List(OrcaEvent.ToolUse("Read", """{"file":"a"}"""))
    )

  test("an answer that throws flushes the withheld message, then rethrows"):
    // The closing message could not be told from narration, so it shows.
    val recorder = new RecordingListener
    val crash = new OrcaFlowException("stdin closed")
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("narration"),
        TurnEvent.AssistantMessageEnd,
        approveBash
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val thrown = intercept[OrcaFlowException]:
      ObservedTurn(live, recorder).drain(_ => throw crash)
    assertEquals(thrown, crash)
    assertEquals(recorder.events, List(OrcaEvent.AssistantMessage("narration")))

  test("AssistantToolCall emits OrcaEvent.ToolUse with the raw input"):
    // The drain passes the raw JSON through unchanged; summarisation happens in
    // the terminal listener, so other listeners see the full input.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantToolCall(
          "Bash",
          """{ "cmd" : "ls",  "extra" : "ignored" }"""
        )
      ),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.ToolUse("Bash", """{ "cmd" : "ls",  "extra" : "ignored" }""")
      )
    )

  test(
    "buffered text deltas flush as one AssistantMessage on AssistantMessageEnd"
  ):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("hello "),
        TurnEvent.AssistantTextDelta("world"),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("hello world"))
    )

  test("empty message (MessageEnd with no deltas) emits nothing"):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(TurnEvent.AssistantMessageEnd),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(recorder.events, Nil)

  test("ToolResult is swallowed (already surfaced via the preceding ToolUse)"):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.ToolResult(Some("Bash"), ok = true, "stdout text")
      ),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(recorder.events, Nil)

  test("ToolDenied emits OrcaEvent.ToolDenied"):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(TurnEvent.ToolDenied("mcp__visdom__agents_md")),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.ToolDenied("mcp__visdom__agents_md", None))
    )

  test("TurnEvent.Error re-emits as OrcaEvent.Error"):
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(TurnEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(recorder.events, List(OrcaEvent.Error("boom")))

  test("AssistantThinkingDelta is swallowed"):
    // Thinking deltas go through their own explicit case branch (not the
    // catch-all), so they need their own test — otherwise removing the
    // case wouldn't break anything.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(TurnEvent.AssistantThinkingDelta("thinking...")),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(recorder.events, Nil)

  test(
    "deltas without a trailing MessageEnd still flush at end-of-stream"
  ):
    // Mid-message subprocess crash: deltas arrive, then EOF before MessageEnd.
    // Without the safety flush the partial agent message would be lost.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("half-finished thou"),
        TurnEvent.AssistantTextDelta("ght")
      ),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("half-finished thought"))
    )

  test(
    "structured mode flushes an unfinished trailing buffer on a clean close"
  ):
    // Structured mode, clean drain, stream ended with deltas and no closing
    // MessageEnd. The JSON payload is always a completed message; an unfinished
    // trailing buffer is never the payload, so it flushes rather than being
    // dropped. Here there is no completed message, so only the partial surfaces.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("""{"answer":"""),
        TurnEvent.AssistantTextDelta("""1""")
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("""{"answer":1"""))
    )

  test(
    "structured mode: an abnormal mid-stream end flushes withheld + partial"
  ):
    // Message 1 completes (withheld, awaiting the next message to decide if it's the
    // payload); message 2 streams deltas, then the stream crashes before its
    // MessageEnd. On an abnormal end nothing is reliably the payload, so both the
    // withheld completed message and the partial flush. The crash is rethrown
    // verbatim.
    val recorder = new RecordingListener
    val crash = new OrcaFlowException("stream died mid-message")
    val live = new CrashingTurn(
      List(
        TurnEvent.AssistantTextDelta("message one"),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantTextDelta("partial "),
        TurnEvent.AssistantTextDelta("two")
      ),
      crash,
      outputSchema = Some("""{"type":"object"}""")
    )
    val thrown = intercept[OrcaFlowException]:
      ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(thrown, crash)
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage("message one"),
        OrcaEvent.AssistantMessage("partial two")
      )
    )

  test(
    "structured mode drops the final message's text (the JSON payload)"
  ):
    // In structured mode the agent's last assistant message IS the JSON
    // payload that the caller will surface via OrcaEvent.StructuredResult.
    // Emitting it here would double-render the result. Intermediate messages
    // still flush so the user sees the agent's prose along the way.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("planning..."),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantTextDelta("""{"answer":42}"""),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("planning..."))
    )

  test(
    "structured mode: a tool call inside the sole message doesn't defeat the " +
      "withhold"
  ):
    // Only activity AFTER a message closed releases it: here the tool call and
    // its result open the very message whose text is the payload, so that message
    // stays withheld and the JSON never renders.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantToolCall("bash", """{"command":"ls"}"""),
        TurnEvent.ToolResult(Some("bash"), true, "file1\nfile2"),
        TurnEvent.AssistantTextDelta("""{"issues":[]}"""),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.ToolUse("bash", """{"command":"ls"}"""))
    )

  test(
    "a completed message's prose renders before the next message's tool call"
  ):
    // claude closes a narration message, then opens a tool-only message: the
    // narration must render above the tool call it announces.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta(
          "Now let me read the existing stats.py file:"
        ),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantToolCall("Read", """{"file":"stats.py"}"""),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantTextDelta("""{"answer":42}"""),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage(
          "Now let me read the existing stats.py file:"
        ),
        OrcaEvent.ToolUse("Read", """{"file":"stats.py"}""")
      )
    )

  test("a Tool-mode structured call withholds its closing prose message"):
    // That message signs off on work the StructuredResult states in full. Both
    // Tool-mode drivers suppress the schema-exit tool call itself, so nothing
    // message-opening follows the sign-off to release it.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("Reading the file first."),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantTextDelta("Reviewed it; no issues found."),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}"""),
      structuredOutputMode = StructuredOutputMode.Tool
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("Reading the file first."))
    )

  test("two back-to-back messages flush independently"):
    // Pins the textBuf.clear() inside the AssistantMessageEnd case so the
    // second message doesn't carry the first message's text.
    val recorder = new RecordingListener
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("message one"),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantTextDelta("message two"),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult)
    )
    val _ = ObservedTurn(live, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage("message one"),
        OrcaEvent.AssistantMessage("message two")
      )
    )
