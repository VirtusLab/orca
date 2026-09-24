package orca.agents

import orca.testkit.ScriptedBackend
import orca.backend.{
  Interaction,
  AgentResult,
  IdScheme,
  SessionSupport,
  TurnRequest,
  ObservedTurn
}
import orca.events.{OrcaEvent, OrcaListener}
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

private case class SessionCommittedAnswer(value: Int) derives JsonData

/** Pins `OrcaEvent.SessionCommitted` emission (ADR 0021 §8) at the two
  * `AgentCall` sites: the structured autonomous retry loop and the interactive
  * path beside `sessions.register`.
  */
class AgentCallSessionCommittedTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  import scala.concurrent.duration.DurationInt

  // Fast schedule so the parse-retry test doesn't spend seconds sleeping.
  private val fastRetry =
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRetries(5)

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        turn: ObservedTurn[B]
    ): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  test(
    "structured autonomous run with a parse-retry emits SessionCommitted exactly once"
  ):
    val backend = new SequencedBackend(
      List("garbage", """{"value":11}"""),
      wireId = "committed-wire"
    )
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    supervised:
      val call = new AgentCall[
        BackendTag.ClaudeCode.type,
        SessionCommittedAnswer
      ](
        backend = backend,
        config = AgentConfig(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = listener,
        interaction = stubInteraction,
        agentName = "claude",
        agentRole = Some("reviewer")
      )
      val answer = call.autonomous.run("question")
      assertEquals(answer, SessionCommittedAnswer(11))
      val committed = seen.get().reverse.collect {
        case e: OrcaEvent.SessionCommitted => e
      }
      // Fires per attempt now (both the failed-parse attempt and the
      // succeeding one commit the same session), so assert dedup-equivalence
      // rather than an exact count: listeners dedup on (harness, clientId,
      // wireId) per the event's scaladoc.
      assert(committed.nonEmpty, "expected at least one SessionCommitted")
      assertEquals(committed.distinct.size, 1, committed)
      assertEquals(committed.head.harness, BackendTag.ClaudeCode)
      assertEquals(committed.head.wireId, Some("committed-wire"))
      assertEquals(committed.head.agent, "claude")
      assertEquals(committed.head.role, Some("reviewer"))

  test("interactive path emits SessionCommitted after register"):
    val clientSid = SessionId[BackendTag.ClaudeCode.type]("client-uuid-cccc")
    val backend =
      new SequencedBackend(List("""{"value":3}"""), wireId = "server-wire-dddd")
    val drivingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          turn: ObservedTurn[B]
      ): AgentResult[B] =
        turn.drain(_ => ())
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    supervised:
      val call = new AgentCall[
        BackendTag.ClaudeCode.type,
        SessionCommittedAnswer
      ](
        backend = backend,
        config = AgentConfig(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = listener,
        interaction = drivingInteraction,
        agentName = "claude"
      )
      val answer = call.interactive.runWithSession(
        "anything",
        clientSid,
        sessionKey = None
      )
      assertEquals(answer, SessionCommittedAnswer(3))
      val committed = seen.get().reverse.collect {
        case e: OrcaEvent.SessionCommitted => e
      }
      assertEquals(committed.size, 1, committed)
      assertEquals(committed.head.clientId, "client-uuid-cccc")
      assertEquals(committed.head.wireId, Some("server-wire-dddd"))
      assertEquals(committed.head.agent, "claude")
      assertEquals(committed.head.role, None)

  /** A durable backend answering pre-scripted outputs under `wireId`. */
  private class SequencedBackend(outputs: List[String], wireId: String)
      extends ScriptedBackend(
        BackendTag.ClaudeCode,
        SessionSupport.durable(IdScheme.ServerMinted, _ => false)
      ):
    private val remaining: AtomicReference[List[String]] =
      AtomicReference(outputs)
    protected def reply(
        turn: TurnRequest[BackendTag.ClaudeCode.type]
    ): AgentResult[BackendTag.ClaudeCode.type] =
      val next = remaining
        .getAndUpdate(_.drop(1))
        .headOption
        .getOrElse(throw new IllegalStateException("ran out of canned outputs"))
      ScriptedBackend.result(next, wireId)
