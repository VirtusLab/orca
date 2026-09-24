package orca.backend

import orca.events.{OrcaEvent, Usage}
import orca.agents.{AutoApprove, BackendTag, WireSessionId}
import orca.testkit.ScriptedTurn
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

class AutonomousDrainTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    wireId = WireSessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage.empty,
    model = None
  )

  test("drain walks every event before returning the result"):
    val live = new ScriptedTurn(
      List(
        TurnEvent.AssistantTextDelta("hi"),
        TurnEvent.AssistantMessageEnd
      ),
      Right(sampleResult)
    )
    assertEquals(
      supervised(AutonomousDrain.drain(live, AutoApprove.All)),
      sampleResult
    )
    assertEquals(live.drained.get(), 2)

  test("ApproveTool under AutoApprove.Only auto-denies and surfaces an Error"):
    // The autonomous drain has no user to ask, but the backend is blocked
    // waiting for our decision. Auto-denying unblocks it; the Error event lets
    // the user see what got blocked. Silently dropping would deadlock.
    val recorder = new RecordingListener
    val decisions = new AtomicReference[List[ApprovalDecision]](Nil)
    val record = (d: ApprovalDecision) =>
      val _ = decisions.updateAndGet(d :: _)
    val live = new ScriptedTurn(
      List(
        TurnEvent
          .ApproveTool("Bash", """{"command":"rm -rf /"}""", record)
      ),
      Right(sampleResult)
    )
    val _ = supervised(
      AutonomousDrain
        .drain(live, AutoApprove.Only(Set("Read")), recorder)
    )
    assertEquals(decisions.get(), List(ApprovalDecision.Deny))
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
    val live = new ScriptedTurn(
      List(TurnEvent.ApproveTool("Bash", "{}", record)),
      Right(sampleResult)
    )
    val _ =
      supervised(AutonomousDrain.drain(live, AutoApprove.All, recorder))
    assertEquals(decisions.get(), List(ApprovalDecision.Deny))
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
    val live = new ScriptedTurn(
      List(TurnEvent.ApproveTool("Bash", "{}", record)),
      Right(sampleResult)
    )
    val _ = supervised(
      AutonomousDrain
        .drain(live, AutoApprove.Only(Set("Bash")), recorder)
    )
    assertEquals(decisions.get(), List(ApprovalDecision.Deny))
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
    val live = new ScriptedTurn(
      List(TurnEvent.UserQuestion("What now?", record)),
      Right(sampleResult)
    )
    val _ =
      supervised(AutonomousDrain.drain(live, AutoApprove.All, recorder))
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
    val live = new ScriptedTurn(
      List(TurnEvent.ApproveTool("Bash", "{}", _ => ())),
      Right(sampleResult)
    )
    val _ =
      supervised(AutonomousDrain.drain(live, AutoApprove.All, recorder))
    assertEquals(
      recorder.events.collect { case e: OrcaEvent.ToolDenied => e },
      List(OrcaEvent.ToolDenied("Bash", None))
    )
