package orca.backend

import orca.agents.{BackendTag, WireSessionId}
import orca.events.Usage
import orca.OrcaFlowException
import orca.subprocess.FakePipedCliProcess
import ox.supervised

/** Base-level grammar suite: drives a minimal fake [[ForkedConversation]]
  * through raw `ConversationEvent`-level sequences (bypassing all wire parsing)
  * and asserts the emitted stream satisfies
  * [[ConversationEventConformance.assertGrammar]]. This is where the turn-
  * boundary grammar is pinned ONCE, for all five backends at once — the base
  * class guarantees it by construction, so the per-backend suites only need to
  * check they translate protocol frames into the right payloads.
  *
  * It covers the exact sequences each broken driver used to route around before
  * task 4A moved enforcement into `EventQueue.enqueue` + the settle helpers:
  * claude's deltas-then-error, codex's tool-only turn, gemini's activity-free
  * result end, claude's suppressed `ask_user`-only turn, and pi's
  * failWith-after-activity — plus the abnormal-termination carve-out the
  * grammar deliberately still allows.
  */
class TurnGrammarTest extends munit.FunSuite:

  /** Fake driver whose `handleLine` interprets each scripted stdout line as a
    * direct `ConversationEvent`-level command. Everything runs on the reader
    * thread inside `handleLine`, exactly as a real driver's enqueues do — so
    * the `openTurn` single-writer confinement is exercised faithfully.
    */
  private class GrammarFakeConversation(source: StreamSource)
      extends ForkedConversation[BackendTag.ClaudeCode.type](
        source = source,
        backendName = "fake"
      ):
    val outputSchema: Option[String] = None

    protected def handleLine(line: String): Unit =
      line match
        case "delta" =>
          eventQueue.enqueue(ConversationEvent.AssistantTextDelta("t"))
        case "thinking" =>
          eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta("t"))
        case "toolcall" =>
          eventQueue.enqueue(ConversationEvent.AssistantToolCall("tool", "{}"))
        case "toolresult" =>
          eventQueue.enqueue(
            ConversationEvent.ToolResult(Some("tool"), true, "ok")
          )
        case "turnend" =>
          eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
        case "error" =>
          eventQueue.enqueue(ConversationEvent.Error("boom"))
        case "succeed" =>
          succeedWith(AgentResult(WireSessionId("fake"), "done", Usage.empty))
        case "fail" =>
          failWith(new OrcaFlowException("boom"))
        case other =>
          throw new IllegalStateException(s"unknown script line: $other")

  /** Run a scripted sequence and return the EMITTED events. `closeAtEnd`
    * signals stdout EOF after the script; leave it on except for the abnormal-
    * termination case, where the script settles nothing and we want the source
    * to just end mid-turn.
    */
  private def runScript(lines: String*): List[ConversationEvent] =
    supervised:
      val process = new FakePipedCliProcess()
      val conv = new GrammarFakeConversation(StreamSource.fromProcess(process))
      lines.foreach(process.enqueueStdout)
      process.closeStdout()
      process.closeStderr()
      conv.events.toList

  test(
    "deltas then failWith injects a closing turn end (claude's is_error bug)"
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

  test("failWith after activity injects a closing turn end (pi's bug)"):
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

  /** Fake driver whose only purpose is counting
    * [[ForkedConversation.onCancelRequested]] invocations, so `cancel()`'s
    * settled-gate can be pinned independently of any backend's own hook body.
    */
  private class HookFakeConversation(source: StreamSource)
      extends ForkedConversation[BackendTag.ClaudeCode.type](
        source = source,
        backendName = "fake"
      ):
    val outputSchema: Option[String] = None
    var cancelRequests: Int = 0
    override protected def onCancelRequested(): Unit = cancelRequests += 1
    protected def handleLine(line: String): Unit =
      line match
        case "succeed" =>
          succeedWith(AgentResult(WireSessionId("fake"), "done", Usage.empty))
        case other =>
          throw new IllegalStateException(s"unknown script line: $other")

  test(
    "onCancelRequested fires once for a genuine mid-turn cancel; repeat cancel() does not re-fire"
  ):
    supervised:
      val process = new FakePipedCliProcess()
      val conv = new HookFakeConversation(StreamSource.fromProcess(process))
      // No settle ever happens — this IS the genuine "torn down mid-turn" case.
      conv.cancel()
      conv.cancel()
      assertEquals(conv.cancelRequests, 1)
      val _ = conv.awaitResult()

  test(
    "onCancelRequested does NOT fire when cancel() runs after the turn already settled"
  ):
    supervised:
      val process = new FakePipedCliProcess()
      val conv = new HookFakeConversation(StreamSource.fromProcess(process))
      process.enqueueStdout("succeed")
      process.closeStdout()
      process.closeStderr()
      conv.events.foreach(_ => ())
      val _ = conv.awaitResult()
      // The routine `finally cancel()` every caller runs after a turn that
      // already succeeded on its own — must be a pure teardown, no hook.
      conv.cancel()
      assertEquals(conv.cancelRequests, 0)

  /** Fake driver whose only purpose is exposing `succeedWith` to a caller on an
    * arbitrary thread, so 12.2's single-writer assertion can be exercised from
    * outside `handleLine` (every real backend only ever calls it from inside
    * `handleLine`, on the reader thread — this deliberately breaks that
    * contract to prove the check fires).
    */
  private class ThreadInvariantFakeConversation(source: StreamSource)
      extends ForkedConversation[BackendTag.ClaudeCode.type](
        source = source,
        backendName = "fake"
      ):
    val outputSchema: Option[String] = None
    protected def handleLine(line: String): Unit =
      line match
        case "succeed" =>
          succeedWith(AgentResult(WireSessionId("fake"), "done", Usage.empty))
        case other =>
          throw new IllegalStateException(s"unknown script line: $other")
    def succeedFromCallerThread(): Unit =
      succeedWith(AgentResult(WireSessionId("off-thread"), "done", Usage.empty))

  test(
    "succeedWith off the reader thread trips the write-side single-thread assertion"
  ):
    supervised:
      val process = new FakePipedCliProcess()
      val conv =
        new ThreadInvariantFakeConversation(StreamSource.fromProcess(process))
      process.enqueueStdout("succeed")
      process.closeStdout()
      process.closeStderr()
      conv.events.foreach(_ => ())
      // Reader thread has now settled once via a real handleLine dispatch —
      // its identity is recorded. A second settle from a genuinely different
      // thread (never legitimate — every backend settles from inside
      // handleLine) must trip the assertion rather than silently no-op.
      val _ = conv.awaitResult()
      var caught: Throwable = null
      val t = new Thread(() =>
        try conv.succeedFromCallerThread()
        catch case e: Throwable => caught = e
      )
      t.start()
      t.join()
      assert(
        caught != null && caught.isInstanceOf[AssertionError],
        s"expected an AssertionError from the off-thread settle; got: $caught"
      )
