package orca.agents

import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  IdScheme,
  SessionSupport
}
import orca.events.{OrcaEvent, OrcaListener, Usage}
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

private case class SessionCommittedAnswer(value: Int) derives JsonData

/** Pins `OrcaEvent.SessionCommitted` emission (ADR 0021 §8) at the two
  * `DefaultAgentCall` sites: the structured autonomous retry loop and the
  * interactive path beside `sessions.register`.
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
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  test(
    "structured autonomous run with a parse-retry emits SessionCommitted exactly once"
  ):
    val backend = new SequencedBackend(List("garbage", """{"value":11}"""))
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    supervised:
      val call = new DefaultAgentCall[
        BackendTag.ClaudeCode.type,
        SessionCommittedAnswer
      ](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
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
      assertEquals(committed.head.harness, BackendTag.ClaudeCode.wireName)
      assertEquals(committed.head.wireId, Some("committed-wire"))
      assertEquals(committed.head.agent, "claude")
      assertEquals(committed.head.role, Some("reviewer"))

  test(
    "structured autonomous run whose retries exhaust still emits SessionCommitted"
  ):
    // First turn drains fine (the backend commits the session on every call,
    // as a real subprocess's drainAndCommit does) but never parses, so every
    // retry re-sends "garbage" until the schedule's 6 attempts (1 + 5
    // retries) are exhausted and the call throws.
    val backend = new SequencedBackend(List.fill(6)("garbage"))
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    supervised:
      val call = new DefaultAgentCall[
        BackendTag.ClaudeCode.type,
        SessionCommittedAnswer
      ](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = listener,
        interaction = stubInteraction,
        agentName = "claude",
        agentRole = Some("reviewer")
      )
      val _ =
        intercept[MalformedAgentOutputException](
          call.autonomous.run("question")
        )
      val committed = seen.get().reverse.collect {
        case e: OrcaEvent.SessionCommitted => e
      }
      assert(committed.nonEmpty, "expected at least one SessionCommitted")
      // Per-attempt firing (dedup is a listener-side concern per the event's
      // scaladoc): every attempt commits the same session, so all payloads
      // must agree.
      assertEquals(committed.distinct.size, 1, committed)
      assertEquals(committed.head.harness, BackendTag.ClaudeCode.wireName)
      assertEquals(committed.head.wireId, Some("committed-wire"))
      assertEquals(committed.head.agent, "claude")
      assertEquals(committed.head.role, Some("reviewer"))

  test("interactive path emits SessionCommitted after register"):
    val clientSid = SessionId[BackendTag.ClaudeCode.type]("client-uuid-cccc")
    val serverSid =
      WireSessionId[BackendTag.ClaudeCode.type]("server-wire-dddd")
    val backend = new SequencedBackend(Nil)
    val drivingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: Conversation[B]
      )(using ox.Ox): AgentResult[B] =
        AgentResult[B](
          wireId = WireSessionId[B](WireSessionId.value(serverSid)),
          output = """{"value":3}""",
          usage = Usage.empty
        )
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    supervised:
      val call = new DefaultAgentCall[
        BackendTag.ClaudeCode.type,
        SessionCommittedAnswer
      ](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = listener,
        interaction = drivingInteraction,
        agentName = "claude"
      )
      val answer = call.interactive.runWithSession("anything", clientSid, None)
      assertEquals(answer, SessionCommittedAnswer(3))
      val committed = seen.get().reverse.collect {
        case e: OrcaEvent.SessionCommitted => e
      }
      assertEquals(committed.size, 1, committed)
      assertEquals(committed.head.clientId, "client-uuid-cccc")
      assertEquals(committed.head.wireId, Some("server-wire-dddd"))
      assertEquals(committed.head.agent, "claude")
      assertEquals(committed.head.role, None)

  /** Returns pre-scripted outputs and commits the session on each drain (as a
    * real subprocess backend's `drainAndCommit` does), so the wire id is known
    * via `sessions.persistableWireId` once `runAutonomous` returns.
    */
  private class SequencedBackend(outputs: List[String])
      extends AgentBackend[BackendTag.ClaudeCode.type]:
    private val remaining: AtomicReference[List[String]] =
      AtomicReference(outputs)
    val workDir: os.Path = os.pwd
    val sessions: SessionSupport[BackendTag.ClaudeCode.type] =
      SessionSupport.durable(IdScheme.ServerMinted, _ => false)
    val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.ClaudeCode.type] =
      val next = remaining
        .getAndUpdate(_.drop(1))
        .headOption
        .getOrElse(throw new IllegalStateException("ran out of canned outputs"))
      val result = AgentResult(
        WireSessionId[BackendTag.ClaudeCode.type]("committed-wire"),
        next,
        Usage.empty
      )
      sessions.commitAfterDrain(session, result.wireId)
      result
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.ClaudeCode.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.ClaudeCode.type] =
      new Conversation[BackendTag.ClaudeCode.type]:
        val outputSchema: Option[String] = None
        def events(using ox.Ox): Iterator[orca.backend.ConversationEvent] =
          Iterator.empty
        def awaitResult()(using ox.Ox) =
          throw new UnsupportedOperationException("test stub")
        def canAskUser: Boolean = false
        def cancel(): Unit = ()
