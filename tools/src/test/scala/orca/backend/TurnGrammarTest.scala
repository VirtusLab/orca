package orca.backend

import orca.agents.{BackendTag, StructuredOutputMode, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

import java.util.concurrent.atomic.AtomicInteger

/** Base-level grammar suite: drives [[StreamConversation]] with a fake
  * [[LineDecoder]] through raw `ConversationEvent`-level sequences (bypassing
  * all wire parsing) and asserts the emitted stream satisfies
  * [[ConversationEventConformance.assertGrammar]]. Pins the turn-boundary
  * grammar once for all backends, since the reader guarantees it by
  * construction.
  */
class TurnGrammarTest extends munit.FunSuite:

  private type Tag = BackendTag.ClaudeCode.type

  /** Interprets each scripted stdout line as a direct `ConversationEvent`-level
    * command.
    */
  private class GrammarDecoder(unsettledEnd: () => Unit)
      extends LineDecoder[Tag, Unit]:
    override def onUnsettledEnd(): Unit = unsettledEnd()
    def backendName: String = "fake"
    def terminalMessageNoun: String = "a settle"
    def init: Unit = ()
    def failedTurnDebit(state: Unit): TurnDebit = TurnDebit.Unobserved
    def line(state: Unit, line: String): Step[Tag, Unit] =
      line match
        case "delta" =>
          Step.continue((), ConversationEvent.AssistantTextDelta("t"))
        case "thinking" =>
          Step.continue((), ConversationEvent.AssistantThinkingDelta("t"))
        case "toolcall" =>
          Step.continue((), ConversationEvent.AssistantToolCall("tool", "{}"))
        case "toolresult" =>
          Step.continue(
            (),
            ConversationEvent.ToolResult(Some("tool"), true, "ok")
          )
        case "turnend" => Step.continue((), ConversationEvent.AssistantTurnEnd)
        case "error"   => Step.continue((), ConversationEvent.Error("boom"))
        case "succeed" =>
          Step.Settle(
            (),
            Nil,
            Settled.Succeeded(
              AgentResult(WireSessionId("fake"), "done", Usage.empty)
            )
          )
        case "fail" =>
          Step.Settle((), Nil, Settled.Failed("boom", TurnDebit.Unobserved))
        case other =>
          throw new IllegalStateException(s"unknown script line: $other")

  private def start(
      process: FakePipedCliProcess,
      onUnsettledEnd: () => Unit = () => ()
  )(using Ox): Conversation[Tag] =
    StreamConversation.start(
      StreamSource.fromProcess(process),
      ConversationSpec(
        openingPrompt = None,
        outputSchema = None,
        structuredOutputMode = StructuredOutputMode.RawText,
        askUser = AskUserChannel.Unavailable
      ),
      GrammarDecoder(onUnsettledEnd)
    )

  /** Run a scripted sequence and return the emitted events. */
  private def runScript(lines: String*): List[ConversationEvent] =
    supervised:
      val process = new FakePipedCliProcess()
      lines.foreach(process.enqueueStdout)
      process.closeStdout()
      process.closeStderr()
      start(process).events.toList

  test(
    "deltas then a failed settle injects a closing turn end (claude's is_error bug)"
  ):
    val events = runScript("delta", "delta", "error", "fail")
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("t"),
        ConversationEvent.AssistantTextDelta("t"),
        ConversationEvent.Error("boom"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("tool-only turn then succeed injects a closing turn end (codex's bug)"):
    val events = runScript("toolcall", "toolresult", "succeed")
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantToolCall("tool", "{}"),
        ConversationEvent.ToolResult(Some("tool"), true, "ok"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("activity-free settle emits no synthetic turn end (gemini's bug)"):
    val events = runScript("succeed")
    assertEquals(events, Nil)
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("a bare turn end with no activity is dropped (claude's ask_user turn)"):
    val events = runScript("turnend", "succeed")
    assertEquals(events, Nil)
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("a failed settle after activity injects a closing turn end (pi's bug)"):
    val events = runScript("toolresult", "fail")
    assertEquals(
      events,
      List(
        ConversationEvent.ToolResult(Some("tool"), true, "ok"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("normal multi-turn happy path is unchanged (no synthetic ends added)"):
    val events =
      runScript(
        "delta",
        "turnend",
        "toolcall",
        "toolresult",
        "turnend",
        "succeed"
      )
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("t"),
        ConversationEvent.AssistantTurnEnd,
        ConversationEvent.AssistantToolCall("tool", "{}"),
        ConversationEvent.ToolResult(Some("tool"), true, "ok"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("Error is neutral: it neither opens nor closes a turn"):
    // The Error opens nothing, so the following bare turn end is an empty turn
    // and gets dropped; the settle then has no open turn to close either.
    val events = runScript("error", "turnend", "succeed")
    assertEquals(events, List(ConversationEvent.Error("boom")))
    ConversationEventConformance.assertGrammar(events, completedNormally = true)

  test("abnormal mid-turn termination leaves the turn legitimately open"):
    // No settle call: the source just ends mid-turn (cancel/crash carve-out).
    // The base must NOT inject a synthetic turn end here.
    val events = runScript("delta")
    assertEquals(events, List(ConversationEvent.AssistantTextDelta("t")))
    ConversationEventConformance.assertGrammar(
      events,
      completedNormally = false
    )

  test("onUnsettledEnd runs once when a cancel ends the stream mid-turn"):
    val unsettledEnds = new AtomicInteger(0)
    supervised:
      val process = new FakePipedCliProcess()
      val conv = start(process, () => unsettledEnds.incrementAndGet(): Unit)
      conv.cancel()
      conv.cancel()
      val _ = conv.awaitResult()
    assertEquals(unsettledEnds.get(), 1)

  test("onUnsettledEnd does not run for a turn that settled"):
    val unsettledEnds = new AtomicInteger(0)
    supervised:
      val process = new FakePipedCliProcess()
      val conv = start(process, () => unsettledEnds.incrementAndGet(): Unit)
      process.enqueueStdout("succeed")
      process.closeStdout()
      process.closeStderr()
      conv.events.foreach(_ => ())
      val _ = conv.awaitResult()
      conv.cancel()
    assertEquals(unsettledEnds.get(), 0)

  test("lines after a settle are ignored"):
    val events = runScript("succeed", "delta")
    assertEquals(events, Nil)
