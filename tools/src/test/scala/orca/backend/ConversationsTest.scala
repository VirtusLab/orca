package orca.backend

import orca.events.{OrcaEvent, Usage}
import orca.agents.{AutoApprove, BackendTag, WireSessionId}
import orca.testkit.ScriptedConversation
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

class ConversationsTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    wireId = WireSessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage.empty
  )

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
        case OrcaEvent.Error(msg, _) => msg.contains("ask_user")
        case _                       => false
      },
      recorder.events
    )

  test("an auto-denied ApproveTool emits ToolDenied"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.ApproveTool("Bash", "{}", _ => ())),
      Right(sampleResult)
    )
    val _ =
      supervised(Conversations.drainAutonomous(conv, AutoApprove.All, recorder))
    assertEquals(
      recorder.events.collect { case e: OrcaEvent.ToolDenied => e },
      List(OrcaEvent.ToolDenied("Bash", None))
    )
