package orca.backend

import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}
import orca.events.{OrcaEvent, OrcaListener, TurnDebit, Usage}
import orca.agents.{AutoApprove, BackendTag, SessionId, WireSessionId}

import ox.{Ox, supervised}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

private class ScriptedConversation(
    eventList: List[ConversationEvent],
    outcome: Either[OrcaInteractiveCancelled, AgentResult[
      BackendTag.Codex.type
    ]],
    val outputSchema: Option[String] = None
) extends Conversation[BackendTag.Codex.type]:
  val drained = new AtomicInteger(0)
  val cancelCount = new AtomicInteger(0)
  def events(using Ox): Iterator[ConversationEvent] =
    eventList.iterator.map { e =>
      val _ = drained.incrementAndGet()
      e
    }
  def awaitResult()(using
      Ox
  ): Either[OrcaInteractiveCancelled, AgentResult[BackendTag.Codex.type]] =
    outcome
  def canAskUser: Boolean = false
  def cancel(): Unit =
    val _ = cancelCount.incrementAndGet()

/** A conversation whose `awaitResult()` throws instead of returning, standing
  * in for a drain that fails mid-turn (e.g. `AgentTurnFailed`).
  */
private class FailingConversation(failure: Throwable)
    extends Conversation[BackendTag.Codex.type]:
  val cancelCount = new AtomicInteger(0)
  def events(using Ox): Iterator[ConversationEvent] = Iterator.empty
  val outputSchema: Option[String] = None
  def awaitResult()(using
      Ox
  ): Either[OrcaInteractiveCancelled, AgentResult[BackendTag.Codex.type]] =
    throw failure
  def canAskUser: Boolean = false
  def cancel(): Unit =
    val _ = cancelCount.incrementAndGet()

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
  def events(using Ox): Iterator[ConversationEvent] =
    eventList.iterator ++ Iterator.continually[ConversationEvent](throw crash)
  def awaitResult()(using
      Ox
  ): Either[OrcaInteractiveCancelled, AgentResult[BackendTag.Codex.type]] =
    throw new IllegalStateException("awaitResult should be unreachable")
  def canAskUser: Boolean = false
  def cancel(): Unit =
    val _ = cancelCount.incrementAndGet()

/** Records every `OrcaEvent` it sees so tests can assert on the emission order
  * without scaffolding a full listener.
  */
private class RecordingListener extends OrcaListener:
  private val log = new AtomicReference[List[OrcaEvent]](Nil)
  def events: List[OrcaEvent] = log.get().reverse
  def onEvent(event: OrcaEvent): Unit =
    val _ = log.updateAndGet(event :: _)

class ConversationsTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    wireId = WireSessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage.empty
  )

  test(
    "drainAndCommit commits the reported wire id against the caller's client id"
  ):
    val client = SessionId.fresh[BackendTag.Codex.type]
    val reportedWire = WireSessionId[BackendTag.Codex.type]("server-thread-42")
    val support = SessionSupport
      .durable[BackendTag.Codex.type](IdScheme.ServerMinted, _ => false)
    val conv = new ScriptedConversation(
      Nil,
      Right(sampleResult.copy(wireId = reportedWire))
    )
    val result = supervised:
      Conversations.drainAndCommit(
        conv,
        client,
        support,
        AutoApprove.All
      )
    assert(result.wireId == reportedWire) // result reports the wire truth
    assert(
      support.persistableWireId(client).contains(reportedWire)
    ) // mapping learned

  test(
    "a plain OrcaFlowException propagates verbatim through drainAndCommit " +
      "(no relabelling)"
  ):
    // A plain OrcaFlowException must reach the caller unchanged — not rewrapped
    // with a backend-specific "CLI failed" prefix.
    val client = SessionId.fresh[BackendTag.Codex.type]
    val support = SessionSupport
      .durable[BackendTag.Codex.type](IdScheme.ServerMinted, _ => false)
    val failure = new OrcaFlowException("boom")
    val conv = new FailingConversation(failure)
    val thrown = intercept[OrcaFlowException]:
      supervised:
        Conversations.drainAndCommit(
          conv,
          client,
          support,
          AutoApprove.All
        )
    assertEquals(thrown, failure)
    assertEquals(thrown.getMessage, "boom")
    assert(support.persistableWireId(client).isEmpty) // never committed

  test(
    "drainAndCommit refuses an empty/unsafe reported wire id and never commits it"
  ):
    // A missing init event (e.g. a crash before `thread.started`) leaves the
    // wire id defaulted to "" upstream; committing that would let a later
    // call resume against an empty session id. The guard throws BEFORE
    // the commit, so the bookkeeping stays clean either way.
    val client = SessionId.fresh[BackendTag.Codex.type]
    val support = SessionSupport
      .durable[BackendTag.Codex.type](IdScheme.ServerMinted, _ => false)
    val conv = new ScriptedConversation(
      Nil,
      Right(
        sampleResult.copy(wireId = WireSessionId[BackendTag.Codex.type](""))
      )
    )
    val thrown = intercept[OrcaFlowException]:
      supervised:
        Conversations.drainAndCommit(
          conv,
          client,
          support,
          AutoApprove.All
        )
    assert(
      thrown.getMessage.contains("invalid session id"),
      thrown.getMessage
    )
    assert(support.persistableWireId(client).isEmpty) // never committed

  test("drainAutonomous walks every event before returning the result"):
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("hi"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult)
    )
    assertEquals(
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All)),
      sampleResult
    )
    assertEquals(conv.drained.get(), 2)

  test("drainAutonomous throws OrcaInteractiveCancelled on Left outcome"):
    val cancelled = new OrcaInteractiveCancelled(TurnDebit.Unobserved)
    val conv = new ScriptedConversation(Nil, Left(cancelled))
    val thrown = intercept[OrcaInteractiveCancelled]:
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All))
    assertEquals(thrown, cancelled)

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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(recorder.events, Nil)

  test("ApproveTool under AutoApprove.Only auto-denies and surfaces an Error"):
    // The autonomous drain has no user to ask, but the subprocess is
    // blocked on stdin waiting for our decision. Auto-denying with a
    // reason unblocks the agent (it can adapt); the Error event lets the
    // user see what got blocked. Silently dropping would deadlock.
    val recorder = new RecordingListener
    val decisions = new AtomicReference[List[ApprovalDecision]](Nil)
    val record = (d: ApprovalDecision) =>
      val _ = decisions.updateAndGet(d :: _)
    val conv = new ScriptedConversation(
      List(
        ConversationEvent
          .ApproveTool("Bash", """{"command":"rm -rf /"}""", record)
      ),
      Right(sampleResult)
    )
    val _ = supervised(
      Conversations
        .drainAutonomous(conv, AutoApprove.Only(Set("Read")), recorder)
    )
    decisions.get() match
      case ApprovalDecision.Deny(Some(reason)) :: Nil =>
        assert(reason.contains("Bash"), reason)
        assert(reason.contains("auto-approve"), reason)
      case other => fail(s"expected Deny with reason; got $other")
    assertEquals(
      recorder.events.collect { case e: OrcaEvent.Error => e.message },
      List(
        "Denied Bash: it is not in the auto-approve set; " +
          "autonomous mode cannot prompt"
      )
    )

  test("ApproveTool under AutoApprove.All blames the ask, not a missing set"):
    // `All` has no set a tool could be missing from — an opencode server whose
    // own config says `permission: ask` asks anyway.
    val recorder = new RecordingListener
    val decisions = new AtomicReference[List[ApprovalDecision]](Nil)
    val record = (d: ApprovalDecision) =>
      val _ = decisions.updateAndGet(d :: _)
    val conv = new ScriptedConversation(
      List(ConversationEvent.ApproveTool("Bash", "{}", record)),
      Right(sampleResult)
    )
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    decisions.get() match
      case ApprovalDecision.Deny(Some(reason)) :: Nil =>
        assert(reason.contains("Bash"), reason)
        assert(!reason.contains("auto-approve"), reason)
      case other => fail(s"expected Deny with reason; got $other")
    assertEquals(
      recorder.events.collect { case e: OrcaEvent.Error => e.message },
      List(
        "Denied Bash: the backend asked for approval itself; " +
          "autonomous mode cannot prompt"
      )
    )

  test("ApproveTool for a tool in the Only set blames the ask, not the set"):
    // opencode ignores orca's auto-approve set and asks on its own account, so
    // a listed tool can still be asked about — the set is not the cause.
    val recorder = new RecordingListener
    val decisions = new AtomicReference[List[ApprovalDecision]](Nil)
    val record = (d: ApprovalDecision) =>
      val _ = decisions.updateAndGet(d :: _)
    val conv = new ScriptedConversation(
      List(ConversationEvent.ApproveTool("Bash", "{}", record)),
      Right(sampleResult)
    )
    val _ = supervised(
      Conversations
        .drainAutonomous(conv, AutoApprove.Only(Set("Bash")), recorder)
    )
    decisions.get() match
      case ApprovalDecision.Deny(Some(reason)) :: Nil =>
        assert(!reason.contains("auto-approve"), reason)
      case other => fail(s"expected Deny with reason; got $other")
    assertEquals(
      recorder.events.collect { case e: OrcaEvent.Error => e.message },
      List(
        "Denied Bash: the backend asked for approval itself; " +
          "autonomous mode cannot prompt"
      )
    )

  test("UserQuestion auto-answers a placeholder and surfaces an Error"):
    // Defensive: the autonomous path never wires the ask_user MCP bridge,
    // so this event should be unreachable. If a future change ever lands
    // one here, the bridge thread is blocked on `respond` — answer with
    // a placeholder rather than leaking it.
    val recorder = new RecordingListener
    val answers = new AtomicReference[List[String]](Nil)
    val record = (s: String) =>
      val _ = answers.updateAndGet(s :: _)
    val conv = new ScriptedConversation(
      List(ConversationEvent.UserQuestion("What now?", record)),
      Right(sampleResult)
    )
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    answers.get() match
      case ans :: Nil => assert(ans.contains("autonomous mode"), ans)
      case other      => fail(s"expected one answer; got $other")
    assert(
      recorder.events.exists {
        case OrcaEvent.Error(msg) => msg.contains("ask_user")
        case _                    => false
      },
      recorder.events
    )

  test("ToolResult is swallowed (already surfaced via the preceding ToolUse)"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ToolResult(Some("Bash"), ok = true, "stdout text")
      ),
      Right(sampleResult)
    )
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(recorder.events, Nil)

  test("UserMessage echo is swallowed (UserPrompt covers it upstream)"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.UserMessage("echo of the opening prompt")),
      Right(sampleResult)
    )
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(recorder.events, Nil)

  test("ConversationEvent.Error re-emits as OrcaEvent.Error"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(
      recorder.events,
      List(OrcaEvent.AssistantMessage("planning..."))
    )

  test(
    "structured mode: a tool call inside the sole turn doesn't defeat the " +
      "withhold"
  ):
    // A single completed turn (activity opened by a tool call, closed once by
    // the trailing AssistantTurnEnd) must stay withheld regardless of what
    // kind of activity preceded the text delta — the one-turn delay only
    // ever concerns TURN COUNT, not the shape of a turn's activity.
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(
      recorder.events,
      List(OrcaEvent.ToolUse("bash", """{"command":"ls"}"""))
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
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(
      recorder.events,
      List(
        OrcaEvent.AssistantMessage("turn one"),
        OrcaEvent.AssistantMessage("turn two")
      )
    )

  test("runAutonomous opens, drains, commits, and cancels exactly once"):
    val client = SessionId.fresh[BackendTag.Codex.type]
    val reportedWire = WireSessionId[BackendTag.Codex.type]("server-thread-7")
    val support = SessionSupport
      .durable[BackendTag.Codex.type](IdScheme.ServerMinted, _ => false)
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("hi"),
        ConversationEvent.AssistantTurnEnd
      ),
      Right(sampleResult.copy(wireId = reportedWire))
    )
    val result =
      Conversations.runAutonomous(
        client,
        support,
        AutoApprove.All,
        OrcaListener.noop
      ):
        conv
    assertEquals(result.wireId, reportedWire)
    assert(support.persistableWireId(client).contains(reportedWire))
    assertEquals(conv.cancelCount.get(), 1)

  test(
    "runAutonomous still cancels when the drain throws AgentTurnFailed"
  ):
    val client = SessionId.fresh[BackendTag.Codex.type]
    val support = SessionSupport
      .durable[BackendTag.Codex.type](IdScheme.ServerMinted, _ => false)
    val failure = new AgentTurnFailed("turn blew up", TurnDebit.Unobserved)
    val conv = new FailingConversation(failure)
    val thrown = intercept[AgentTurnFailed]:
      Conversations.runAutonomous(
        client,
        support,
        AutoApprove.All,
        OrcaListener.noop
      ):
        conv
    assertEquals(thrown, failure)
    assertEquals(conv.cancelCount.get(), 1)
    assert(support.persistableWireId(client).isEmpty) // never committed
