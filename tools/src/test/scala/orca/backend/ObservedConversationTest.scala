package orca.backend

import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.agents.{BackendTag, StructuredOutputMode, WireSessionId}
import orca.events.{OrcaEvent, Usage}
import orca.testkit.ScriptedConversation

import java.util.concurrent.atomic.AtomicInteger

/** A conversation whose event stream throws partway through iteration, standing
  * in for a subprocess that dies mid-turn: the scripted events are yielded
  * first, then the next `foreach` step raises `crash`. `awaitResult()` is never
  * reached because the drain's event loop throws before it.
  */
private class CrashingConversation(
    eventList: List[ConversationEvent],
    crash: Throwable,
    override val outputSchema: Option[String] = None
) extends Conversation[BackendTag.Codex.type]:
  val cancelCount = new AtomicInteger(0)
  def events: Iterator[ConversationEvent] =
    eventList.iterator ++ Iterator.continually[ConversationEvent](throw crash)
  def awaitResult()
      : Either[OrcaInteractiveCancelled, AgentResult[BackendTag.Codex.type]] =
    throw new IllegalStateException("awaitResult should be unreachable")
  def canAskUser: Boolean = false
  def cancel(): Unit =
    val _ = cancelCount.incrementAndGet()

class ObservedConversationTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    wireId = WireSessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage.empty
  )

  private val approveBash = ConversationEvent.ApproveTool(
    "Bash",
    """{"command":"ls"}""",
    _ => ()
  )

  test("the opening UserMessage surfaces as OrcaEvent.UserPrompt"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.UserMessage("fix the build")),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(recorder.events, List(OrcaEvent.UserPrompt("fix the build")))

  test("a tool call reaches the listener before the next event is answered"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantToolCall("Read", """{"file":"a"}"""),
        approveBash
      ),
      Right(sampleResult)
    )
    var seenAtAnswer: List[OrcaEvent] = Nil
    val _ = ObservedConversation(conv, recorder).drain: _ =>
      seenAtAnswer = recorder.events
    assertEquals(
      seenAtAnswer,
      List(OrcaEvent.ToolUse("Read", """{"file":"a"}"""))
    )

  test("an answer that throws flushes the withheld turn, then rethrows"):
    // The closing turn could not be told from narration, so it shows.
    val recorder = new RecordingListener
    val crash = new OrcaFlowException("stdin closed")
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("narration"),
        ConversationEvent.AssistantTurnEnd,
        approveBash
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val thrown = intercept[OrcaFlowException]:
      ObservedConversation(conv, recorder).drain(_ => throw crash)
    assertEquals(thrown, crash)
    assertEquals(recorder.events, List(OrcaEvent.AssistantMessage("narration")))

  test("AssistantToolCall emits OrcaEvent.ToolUse with the raw input"):
    // The drain passes the raw JSON through unchanged; summarisation happens in
    // the terminal listener, so other listeners see the full input.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantToolCall(
          "Bash",
          """{ "cmd" : "ls",  "extra" : "ignored" }"""
        )
      ),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.ToolUse("Bash", """{ "cmd" : "ls",  "extra" : "ignored" }""")
      )
    )

  test(
    "buffered text deltas flush as one AssistantMessage on AssistantTurnEnd"
  ):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("hello "),
        ConversationEvent.AssistantTextDelta("world"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("hello world"))
    )

  test("empty turn (TurnEnd with no deltas) emits nothing"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantTurnEnd),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(recorder.events, Nil)

  test("ToolResult is swallowed (already surfaced via the preceding ToolUse)"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ToolResult(Some("Bash"), ok = true, "stdout text")
      ),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(recorder.events, Nil)

  test("ToolDenied emits OrcaEvent.ToolDenied"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.ToolDenied("mcp__visdom__agents_md")),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.ToolDenied("mcp__visdom__agents_md", None))
    )

  test("ConversationEvent.Error re-emits as OrcaEvent.Error"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(recorder.events, List(OrcaEvent.Error("boom")))

  test("AssistantThinkingDelta is swallowed"):
    // Thinking deltas go through their own explicit case branch (not the
    // catch-all), so they need their own test — otherwise removing the
    // case wouldn't break anything.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantThinkingDelta("thinking...")),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(recorder.events, Nil)

  test(
    "deltas without a trailing TurnEnd still flush at end-of-stream"
  ):
    // Mid-turn subprocess crash: deltas arrive, then EOF before TurnEnd.
    // Without the safety flush the partial agent message would be lost.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("half-finished thou"),
        ConversationEvent.AssistantTextDelta("ght")
      ),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("half-finished thought"))
    )

  test(
    "structured mode flushes an unfinished trailing buffer on a clean close"
  ):
    // Structured mode, clean drain, stream ended with deltas and no closing
    // TurnEnd. The JSON payload is always a completed turn; an unfinished
    // trailing buffer is never the payload, so it flushes rather than being
    // dropped. Here there is no completed turn, so only the partial surfaces.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("""{"answer":"""),
        ConversationEvent.AssistantTextDelta("""1""")
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("""{"answer":1"""))
    )

  test(
    "structured mode: an abnormal mid-stream end flushes withheld + partial"
  ):
    // Turn 1 completes (withheld, awaiting the next turn to decide if it's the
    // payload); turn 2 streams deltas, then the stream crashes before its
    // TurnEnd. On an abnormal end nothing is reliably the payload, so both the
    // withheld completed turn and the partial flush. The crash is rethrown
    // verbatim.
    val recorder = new RecordingListener
    val crash = new OrcaFlowException("stream died mid-turn")
    val conv = new CrashingConversation(
      List(
        ConversationEvent.AssistantTextDelta("turn one"),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("partial "),
        ConversationEvent.AssistantTextDelta("two")
      ),
      crash,
      outputSchema = Some("""{"type":"object"}""")
    )
    val thrown = intercept[OrcaFlowException]:
      ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(thrown, crash)
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage("turn one"),
        OrcaEvent.AssistantMessage("partial two")
      )
    )

  test(
    "structured mode drops the final turn's text (the JSON payload)"
  ):
    // In structured mode the agent's last assistant message IS the JSON
    // payload that the caller will surface via OrcaEvent.StructuredResult.
    // Emitting it here would double-render the result. Intermediate turns
    // still flush so the user sees the agent's prose along the way.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("planning..."),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("""{"answer":42}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("planning..."))
    )

  test(
    "structured mode: a tool call inside the sole turn doesn't defeat the " +
      "withhold"
  ):
    // Only activity AFTER a turn closed releases it: here the tool call and
    // its result open the very turn whose text is the payload, so that turn
    // stays withheld and the JSON never renders.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantToolCall("bash", """{"command":"ls"}"""),
        ConversationEvent.ToolResult(Some("bash"), true, "file1\nfile2"),
        ConversationEvent.AssistantTextDelta("""{"issues":[]}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.ToolUse("bash", """{"command":"ls"}"""))
    )

  test("a completed turn's prose renders before the next turn's tool call"):
    // claude closes a narration turn, then opens a tool-only turn: the
    // narration must render above the tool call it announces.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta(
          "Now let me read the existing stats.py file:"
        ),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantToolCall("Read", """{"file":"stats.py"}"""),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("""{"answer":42}"""),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage(
          "Now let me read the existing stats.py file:"
        ),
        OrcaEvent.ToolUse("Read", """{"file":"stats.py"}""")
      )
    )

  test("a Tool-mode structured call withholds its closing prose turn"):
    // That turn signs off on work the StructuredResult states in full. Both
    // Tool-mode drivers suppress the schema-exit tool call itself, so nothing
    // turn-opening follows the sign-off to release it.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("Reading the file first."),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("Reviewed it; no issues found."),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}"""),
      structuredOutputMode = StructuredOutputMode.Tool
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("Reading the file first."))
    )

  test("two back-to-back turns flush independently"):
    // Pins the textBuf.clear() inside the AssistantTurnEnd case so the
    // second turn doesn't carry the first turn's text.
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("turn one"),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantTextDelta("turn two"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage("turn one"),
        OrcaEvent.AssistantMessage("turn two")
      )
    )
