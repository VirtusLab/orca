package orca.backend

import orca.agents.{BackendTag, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised, timeout}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*

/** Base-level grammar suite: drives [[DecodedTurn]] with a fake [[LineDecoder]]
  * through raw `TurnEvent`-level sequences (bypassing all wire parsing) and
  * asserts the emitted stream satisfies [[TurnEventConformance.assertGrammar]].
  * Pins the message-boundary grammar once for all backends, since the reader
  * guarantees it by construction.
  */
class MessageGrammarTest extends munit.FunSuite:

  private type Tag = BackendTag.ClaudeCode.type

  /** Interprets each scripted stdout line as a direct `TurnEvent`-level
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
          Step.continue((), TurnEvent.AssistantTextDelta("t"))
        case "thinking" =>
          Step.continue((), TurnEvent.AssistantThinkingDelta("t"))
        case "toolcall" =>
          Step.continue((), TurnEvent.AssistantToolCall("tool", "{}"))
        case "toolresult" =>
          Step.continue(
            (),
            TurnEvent.ToolResult(Some("tool"), true, "ok")
          )
        case "messageend" => Step.continue((), TurnEvent.AssistantMessageEnd)
        case "error"      => Step.continue((), TurnEvent.Error("boom"))
        case "succeed" =>
          Step.Settle(
            (),
            Nil,
            Settled.Succeeded(
              AgentResult(
                WireSessionId("fake"),
                "done",
                Usage.empty,
                model = None
              )
            )
          )
        case "fail" =>
          Step.Settle((), Nil, Settled.Failed("boom", TurnDebit.Unobserved))
        case other =>
          throw new IllegalStateException(s"unknown script line: $other")

  private def start(
      process: FakePipedCliProcess,
      onUnsettledEnd: () => Unit = () => ()
  )(using Ox): LiveTurn[Tag] =
    DecodedTurn.start(
      StreamSource.fromProcess(process),
      DecodedTurnSpec(
        openingPrompt = None,
        outputSchema = None,
        askUser = AskUserChannel.Unavailable
      ),
      GrammarDecoder(onUnsettledEnd)
    )

  /** Run a scripted sequence and return the emitted events. */
  private def runScript(lines: String*): List[TurnEvent] =
    supervised:
      val process = new FakePipedCliProcess()
      lines.foreach(process.enqueueStdout)
      process.closeStdout()
      process.closeStderr()
      start(process).events.toList

  test(
    "deltas then a failed settle injects a closing message end (claude's is_error bug)"
  ):
    val events = runScript("delta", "delta", "error", "fail")
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("t"),
        TurnEvent.AssistantTextDelta("t"),
        TurnEvent.Error("boom"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test(
    "tool-only message then succeed injects a closing message end (codex's bug)"
  ):
    val events = runScript("toolcall", "toolresult", "succeed")
    assertEquals(
      events,
      List(
        TurnEvent.AssistantToolCall("tool", "{}"),
        TurnEvent.ToolResult(Some("tool"), true, "ok"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test("activity-free settle emits no synthetic message end (gemini's bug)"):
    val events = runScript("succeed")
    assertEquals(events, Nil)
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test(
    "a bare message end with no activity is dropped (claude's ask_user message)"
  ):
    val events = runScript("messageend", "succeed")
    assertEquals(events, Nil)
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test(
    "a failed settle after activity injects a closing message end (pi's bug)"
  ):
    val events = runScript("toolresult", "fail")
    assertEquals(
      events,
      List(
        TurnEvent.ToolResult(Some("tool"), true, "ok"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test(
    "normal multi-message happy path is unchanged (no synthetic ends added)"
  ):
    val events =
      runScript(
        "delta",
        "messageend",
        "toolcall",
        "toolresult",
        "messageend",
        "succeed"
      )
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("t"),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantToolCall("tool", "{}"),
        TurnEvent.ToolResult(Some("tool"), true, "ok"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test("Error is neutral: it neither opens nor closes a message"):
    // The Error opens nothing, so the following bare message end closes an
    // empty message and gets dropped; the settle then has no open message to close either.
    val events = runScript("error", "messageend", "succeed")
    assertEquals(events, List(TurnEvent.Error("boom")))
    TurnEventConformance.assertGrammar(events, completedNormally = true)

  test("abnormal mid-message termination leaves the message legitimately open"):
    // No settle call: the source just ends mid-message (cancel/crash carve-out).
    // The base must NOT inject a synthetic message end here.
    val events = runScript("delta")
    assertEquals(events, List(TurnEvent.AssistantTextDelta("t")))
    TurnEventConformance.assertGrammar(
      events,
      completedNormally = false
    )

  test("onUnsettledEnd runs once when a cancel ends the stream mid-turn"):
    val unsettledEnds = new AtomicInteger(0)
    supervised:
      val process = new FakePipedCliProcess()
      val live = start(process, () => unsettledEnds.incrementAndGet(): Unit)
      live.cancel()
      live.cancel()
      assertEquals(unsettledEnds.get(), 1)

  test("onUnsettledEnd does not run for a turn that settled"):
    val unsettledEnds = new AtomicInteger(0)
    supervised:
      val process = new FakePipedCliProcess()
      val live = start(process, () => unsettledEnds.incrementAndGet(): Unit)
      process.enqueueStdout("succeed")
      process.closeStdout()
      process.closeStderr()
      live.events.foreach(_ => ())
      val _ = live.awaitResult()
      live.cancel()
    assertEquals(unsettledEnds.get(), 0)

  test("cancel frees a reader blocked on a full channel"):
    // The channel holds 1024 events, so once line 1025 is handed out the reader
    // is about to block sending it.
    val channelFull = new CountDownLatch(1)
    val stopped = new AtomicBoolean(false)
    val endless = new StreamSource:
      def lines: Iterator[String] =
        Iterator
          .from(1)
          .takeWhile(_ => !stopped.get())
          .map: n =>
            if n == 1025 then channelFull.countDown()
            "delta"
      def errorLines: Iterator[String] = Iterator.empty
      def interrupt(): Unit = stopped.set(true)
      def tryExitCode: Option[Int] = Some(0)
    supervised:
      val live = DecodedTurn.start(
        endless,
        DecodedTurnSpec(
          openingPrompt = None,
          outputSchema = None,
          askUser = AskUserChannel.Unavailable
        ),
        GrammarDecoder(() => ())
      )
      channelFull.await()
      timeout(10.seconds)(live.cancel())

  test("lines after a settle are ignored"):
    val events = runScript("succeed", "delta")
    assertEquals(events, Nil)
