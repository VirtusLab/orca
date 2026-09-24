package orca.tools.claude

import orca.testkit.{ScriptedBackend, ScriptedConversation}
import orca.{AgentTurnFailed, OrcaFlowException, OrcaInteractiveCancelled}
import orca.agents.{
  AutoApprove,
  BackendTag,
  Enforcement,
  EnforcementCell,
  JsonData,
  Model,
  AgentConfig,
  SessionId,
  StructuredOutputMode,
  ToolSet,
  TurnDispatch,
  WireSessionId
}
import orca.events.{Announcement, OrcaEvent, OrcaListener, TurnDebit, Usage}
import orca.testkit.Usages.usage

import orca.backend.{
  AgentResult,
  Conversation,
  ConversationEvent,
  IdScheme,
  Interaction,
  SessionSupport,
  TurnRequest
}
import orca.agents.{AgentCall, DefaultPrompts, PromptEvent}
import ox.supervised

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

case class Answer(value: Int) derives JsonData

/** Fake backend that returns a pre-scripted sequence of outputs and records the
  * prompts it was asked to run. `mode` is the declared
  * [[StructuredOutputMode]]; defaults to the text contract, overridden by the
  * test pinning the Tool-mode instruction.
  *
  * Its sessions are a real capability: tests observe the mapping the framework
  * registered via `sessions.persistableWireId`.
  */
class SequencedBackend(
    outputs: List[String],
    mode: StructuredOutputMode = StructuredOutputMode.RawText
) extends ScriptedBackend(
      BackendTag.ClaudeCode,
      SessionSupport.durable(IdScheme.ServerMinted, _ => false)
    ):
  private val remaining: AtomicReference[List[String]] =
    AtomicReference(outputs)
  private val promptsRef: AtomicReference[List[String]] =
    AtomicReference(Nil)
  private val seenSchemas: AtomicReference[List[Option[String]]] =
    AtomicReference(Nil)
  def prompts: List[String] = promptsRef.get().reverse

  /** `outputSchema` values the backend received, in invocation order. Lets
    * tests assert that `AgentCall` actually passes `Some(<schema>)` rather than
    * dropping to `None`.
    */
  def schemas: List[Option[String]] = seenSchemas.get().reverse

  override def structuredOutputMode: StructuredOutputMode = mode

  protected def reply(
      turn: TurnRequest[BackendTag.ClaudeCode.type]
  ): AgentResult[BackendTag.ClaudeCode.type] =
    val _ = seenSchemas.updateAndGet(turn.outputSchema :: _)
    val _ = promptsRef.updateAndGet(turn.prompt :: _)
    val next = remaining
      .getAndUpdate(rs => rs.drop(1))
      .headOption
      .getOrElse(throw new IllegalStateException("ran out of canned outputs"))
    ScriptedBackend.result(next, "sess-test")

class AgentCallTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token once for the suite.
  private given orca.InStage = orca.InStage.unsafe

  import scala.concurrent.duration.DurationInt

  // Fast schedule so retry tests don't spend seconds sleeping between attempts.
  private val fastRetry =
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRetries(5)

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: orca.backend.ObservedConversation[B]
    ): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  /** Ends an interactive turn at once with `output`, on wire id `wireId`. */
  private def finishingInteraction(
      wireId: String,
      output: String
  ): Interaction =
    new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.ObservedConversation[B]
      ): AgentResult[B] =
        AgentResult[B](
          wireId = WireSessionId[B](wireId),
          output = output,
          usage = Usage.empty
        )

  private def makeCall(
      backend: SequencedBackend
  ): AgentCall[BackendTag.ClaudeCode.type, Answer] =
    new AgentCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      config = AgentConfig(retrySchedule = fastRetry),
      prompts = DefaultPrompts,
      events = orca.events.OrcaListener.noop,
      interaction = stubInteraction,
      agentName = "claude"
    )

  test(
    "autonomous retries on parse failure and eventually returns a parsed value"
  ):
    val backend = new SequencedBackend(
      List(
        "not even json",
        "still not json",
        """{"value":42}"""
      )
    )
    supervised:
      val answer = makeCall(backend).autonomous.run("what is the answer?")
      assertEquals(answer, Answer(42))
      val Seq(first, second, third) = backend.prompts: @unchecked
      assert(
        !first.contains("not even json"),
        "first attempt should be the initial prompt, not a retry"
      )
      assert(
        second.contains("not even json"),
        "second attempt must quote the first failure"
      )
      assert(
        third.contains("still not json"),
        "third attempt must quote the second failure"
      )

  test("autonomous succeeds on the first attempt when the response parses"):
    val backend = new SequencedBackend(List("""{"value":7}"""))
    supervised:
      val answer = makeCall(backend).autonomous.run("a question")
      assertEquals(answer, Answer(7))
      assertEquals(backend.prompts.size, 1)

  test(
    "run(session = sid) retries against the same sessionId on parse failure"
  ):
    val backend = new SequencedBackend(
      List("garbage", """{"value":11}""")
    )
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-under-test")
    supervised:
      val answer =
        makeCall(backend).autonomous.runWithSession(
          "next step",
          sid,
          sessionKey = None,
          promptEvent = PromptEvent.Emit
        )
      assertEquals(answer, Answer(11))
      val Seq(first, second) = backend.prompts: @unchecked
      assert(
        !first.contains("garbage"),
        "first attempt must be the original prompt, not a retry"
      )
      assert(
        second.contains("garbage"),
        "second attempt must quote the first failure as a corrective prompt"
      )

  test(
    "autonomous emits a StructuredResult with raw + Announce-derived summary"
  ):
    // A specific Announce[Answer] wins over Announce.default; the call
    // emits a single StructuredResult event carrying both the raw
    // payload (the agent's JSON) and the summary derived from Announce.
    given orca.agents.Announce[Answer] =
      orca.agents.Announce.from(a => s"answer is ${a.value}")
    val backend = new SequencedBackend(List("""{"value":99}"""))
    val seen = AtomicReference[List[orca.events.OrcaEvent]](Nil)
    val call = new AgentCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      config = AgentConfig(retrySchedule = fastRetry),
      prompts = DefaultPrompts,
      events = (e: orca.events.OrcaEvent) => {
        val _ = seen.updateAndGet(e :: _)
      },
      interaction = stubInteraction,
      agentName = "claude"
    )
    supervised:
      val _ = call.autonomous.run("anything")
      val structured = seen.get().collect {
        case orca.events.OrcaEvent.StructuredResult(raw, announcement, _) =>
          (raw, announcement)
      }
      assertEquals(
        structured,
        List(("""{"value":99}""", Announcement.Say("answer is 99")))
      )

  test(
    "autonomous instruction follows the backend's declared structured-output mode"
  ):
    // The prompt that actually reaches the backend must match its wire: a
    // Tool-mode backend gets the StructuredOutput-tool instruction, a RawText
    // backend the raw-JSON contract. Pins the AgentCall-side branch on
    // `backend.structuredOutputMode` (capability, not backend identity).
    val toolBackend = new SequencedBackend(
      List("""{"value":3}"""),
      mode = StructuredOutputMode.Tool
    )
    val rawBackend = new SequencedBackend(List("""{"value":4}"""))
    supervised:
      val _ = makeCall(toolBackend).autonomous.run("anything")
      val _ = makeCall(rawBackend).autonomous.run("anything")
      val toolPrompt = toolBackend.prompts.head
      val rawPrompt = rawBackend.prompts.head
      assert(
        toolPrompt.contains("calling the StructuredOutput tool"),
        s"Tool-mode prompt must name the tool; got: $toolPrompt"
      )
      assert(!toolPrompt.contains("raw JSON only"))
      assert(rawPrompt.contains("raw JSON only"))
      assert(!rawPrompt.contains("StructuredOutput"))

  test("the corrective retry prompt follows the same structured-output mode"):
    // The initial prompt is not the only one that has to match the wire — the
    // retry after a parse failure carries the same delivery instruction.
    val backend = new SequencedBackend(
      List("not even json", """{"value":5}"""),
      mode = StructuredOutputMode.Tool
    )
    supervised:
      val _ = makeCall(backend).autonomous.run("anything")
      val corrective = backend.prompts(1)
      assert(corrective.contains("calling the StructuredOutput tool"))
      assert(!corrective.contains("raw JSON only"))

  test(
    "autonomous forwards a Some(schema) to backend.runAutonomous"
  ):
    // Structured calls must carry their generated schema down to the backend so
    // the conversation knows it's in structured mode (drain suppresses raw JSON)
    // and the CLI gets `--json-schema`/`--output-schema`.
    val backend = new SequencedBackend(List("""{"value":1}"""))
    supervised:
      val _ = makeCall(backend).autonomous.run("anything")
      backend.schemas match
        case Some(s) :: _ =>
          assert(
            s.contains("\"value\"") && s.contains("\"integer\""),
            s"schema should describe Answer's `value: Int` field; got: $s"
          )
        case other =>
          fail(s"expected Some(schema) as the first call; got $other")

  test(
    "autonomous threads its `events` listener to backend.runAutonomous"
  ):
    // Without this wiring, structured calls (which is what every reviewer
    // uses) lose tool-use / assistant-message visibility — the per-turn
    // events fire only when the backend gets the same listener the
    // AgentCall was constructed with.
    val backend = new SequencedBackend(List("""{"value":1}""")):
      override protected[orca] def open(
          turn: TurnRequest[BackendTag.ClaudeCode.type]
      )(using ox.Ox): Conversation[BackendTag.ClaudeCode.type] =
        new ScriptedConversation(
          List(ConversationEvent.AssistantToolCall("Read", "{}")),
          Right(reply(turn)),
          turn.outputSchema
        )
    val received =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val myListener: OrcaListener = e => {
      val _ = received.updateAndGet(e :: _)
    }
    supervised:
      val _ = new AgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        config = AgentConfig(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = myListener,
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      assert(
        received
          .get()
          .contains(OrcaEvent.ToolUse("Read", "{}", Some("claude"))),
        received.get()
      )

  test("autonomous StructuredResult names the agent that produced it"):
    // Reviewers run in parallel, so an unnamed result line can't be told apart.
    val backend = new SequencedBackend(List("""{"value":3}"""))
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    supervised:
      val _ = new AgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        config = AgentConfig(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = (e: OrcaEvent) => {
          val _ = seen.updateAndGet(e :: _)
        },
        interaction = stubInteraction,
        agentName = "reviewer-a"
      ).autonomous.run("anything")
      assertEquals(
        seen.get().collect { case r: OrcaEvent.StructuredResult => r.agent },
        List(Some("reviewer-a"))
      )

  test(
    "autonomous emits UserPrompt(serialized) once, plus once per retry"
  ):
    // Pins the input-visibility contract: the framework must surface the
    // human-readable input (and any corrective retry prompt) so a listener
    // — terminal or otherwise — can show the user what was sent. Two
    // failed attempts followed by a successful one means three UserPrompts:
    // the original input + two corrective prompts. Dropping the emit on
    // either path would fail this test.
    val backend = new SequencedBackend(
      List("garbage one", "garbage two", """{"value":5}""")
    )
    val seen = AtomicReference[List[orca.events.OrcaEvent]](Nil)
    supervised:
      val _ = new AgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        config = AgentConfig(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = (e: orca.events.OrcaEvent) => {
          val _ = seen.updateAndGet(e :: _)
        },
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("original question")
      val prompts = seen.get().reverse.collect {
        case orca.events.OrcaEvent.UserPrompt(text) => text
      }
      assertEquals(prompts.size, 3, prompts)
      assertEquals(prompts.head, "original question")
      assert(
        prompts(1).contains("garbage one"),
        s"first retry prompt must quote the first failure; got: ${prompts(1)}"
      )
      assert(
        prompts(2).contains("garbage two"),
        s"second retry prompt must quote the second failure; got: ${prompts(2)}"
      )

  test(
    "autonomous does not retry AgentTurnFailed and attributes it to the agent"
  ):
    // A turn that ran and failed (e.g. "Prompt is too long") leaves the
    // session id registered; retrying would only collide ("already in use").
    // It must propagate after a single attempt, named + sized.
    val calls = new AtomicInteger(0)
    val backend = new SequencedBackend(Nil):
      override protected def reply(
          turn: TurnRequest[BackendTag.ClaudeCode.type]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        val _ = calls.incrementAndGet()
        throw new AgentTurnFailed(
          "Prompt is too long",
          TurnDebit.Unobserved
        )
    supervised:
      val ex = intercept[AgentTurnFailed]:
        makeCall(backend).autonomous.run("the original input")
      assertEquals(calls.get(), 1, "AgentTurnFailed must not be retried")
      assert(ex.getMessage.contains("agent 'claude'"), ex.getMessage)
      assert(ex.getMessage.contains("Prompt is too long"), ex.getMessage)
      // runAutonomousWithRetry's re-attribution rewrap must thread the original
      // AgentTurnFailed through as the cause, not just splice its message into
      // the new one and drop it.
      assert(
        ex.getCause != null && ex.getCause.getMessage == "Prompt is too long",
        s"expected the original AgentTurnFailed as cause; got: ${ex.getCause}"
      )

  // `Unobserved` claims the protocol reported nothing, so nothing may be
  // emitted: an all-zero UnpricedTurn would read as a turn measured at zero.
  test("an Unobserved debit emits no UnpricedTurn"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val backend = new SequencedBackend(Nil):
      override protected def reply(
          turn: TurnRequest[BackendTag.ClaudeCode.type]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        throw new AgentTurnFailed("no usage on the wire", TurnDebit.Unobserved)
    supervised:
      val _ = intercept[AgentTurnFailed]:
        new AgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = backend,
          config = AgentConfig(),
          prompts = DefaultPrompts,
          events = listener,
          interaction = stubInteraction,
          agentName = "claude"
        ).autonomous.run("anything")
      assertEquals(
        seen.get().collect { case t: OrcaEvent.UnpricedTurn => t },
        Nil
      )

  // The only failure route that does attempt arithmetic: a retry re-sends the
  // prompt, so the failed second turn is attempt 2 and separable from the first.
  test("a retry that fails after the model ran debits the second attempt"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val spent = usage(70L, 6L)
    val calls = new AtomicInteger(0)
    // Turn 1 runs and returns unparseable output; the corrective retry's turn
    // runs too, then fails with what it spent.
    val backend = new SequencedBackend(List("not json")):
      override protected def reply(
          turn: TurnRequest[BackendTag.ClaudeCode.type]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        if calls.incrementAndGet() == 1 then super.reply(turn)
        else
          throw new AgentTurnFailed(
            "provider error",
            TurnDebit.Observed(spent, None)
          )
    supervised:
      val _ = intercept[AgentTurnFailed]:
        new AgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = backend,
          config = AgentConfig(retrySchedule = fastRetry),
          prompts = DefaultPrompts,
          events = listener,
          interaction = stubInteraction,
          agentName = "claude"
        ).autonomous.run("anything")
      assertEquals(
        seen.get().reverse.collect { case t: OrcaEvent.UnpricedTurn =>
          t.turn
        },
        List(1, 2)
      )

  test(
    "autonomous still retries a non-AgentTurnFailed backend failure"
  ):
    // A pre-spawn open failure (transient broken pipe) is retryable — the
    // session was never registered. Pins that the AgentTurnFailed carve-out
    // didn't disable transient-failure retries.
    val calls = new AtomicInteger(0)
    val backend = new SequencedBackend(List("""{"value":8}""")):
      override protected def reply(
          turn: TurnRequest[BackendTag.ClaudeCode.type]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        if calls.getAndIncrement() == 0 then
          throw new OrcaFlowException(
            "Failed to open claude stream-json session: Broken pipe"
          )
        else super.reply(turn)
    supervised:
      val answer = makeCall(backend).autonomous.run("q")
      assertEquals(answer, Answer(8))
      assertEquals(calls.get(), 2, "transient failure should be retried once")

  // Ctrl-C at an interactive prompt abandons a turn the user is still billed
  // for; the conversation carries what it spent on the cancellation itself.
  test("an interactive turn cancelled after the model ran emits UnpricedTurn"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val spent = usage(90L, 4L)
    val cancellingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.ObservedConversation[B]
      ): AgentResult[B] =
        throw new OrcaInteractiveCancelled(
          TurnDebit.Observed(spent, Some(Model("claude-sonnet-5")))
        )
    supervised:
      val _ = intercept[OrcaInteractiveCancelled]:
        new AgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = new SequencedBackend(List("""{"value":1}""")),
          config = AgentConfig(),
          prompts = DefaultPrompts,
          events = listener,
          interaction = cancellingInteraction,
          agentName = "claude"
        ).interactive.run("anything")
      assertEquals(
        seen.get().collect { case t: OrcaEvent.UnpricedTurn => t.usage },
        List(spent)
      )

  // The autonomous path emits a failed turn's debit; the interactive one runs
  // the same models against the same bills and used to report nothing.
  test(
    "an interactive turn failing after the model ran still emits UnpricedTurn"
  ):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val spent = usage(120L, 8L, Some(BigDecimal("0.0031")))
    val failingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.ObservedConversation[B]
      ): AgentResult[B] =
        throw new AgentTurnFailed(
          "provider error",
          TurnDebit.Observed(spent, Some(Model("claude-sonnet-5")))
        )
    supervised:
      val _ = intercept[AgentTurnFailed]:
        new AgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = new SequencedBackend(List("""{"value":1}""")),
          config = AgentConfig(),
          prompts = DefaultPrompts,
          events = listener,
          interaction = failingInteraction,
          agentName = "claude"
        ).interactive.run("anything")
      assertEquals(
        seen.get().collect { case t: OrcaEvent.UnpricedTurn =>
          (t.usage, t.model)
        },
        List((spent, Some(Model("claude-sonnet-5"))))
      )

  /** [[SequencedBackend]] whose gate is prompt-deep, so a read-only turn has a
    * shortfall to report.
    */
  private class PromptOnlyBackend
      extends SequencedBackend(List("""{"value":5}""")):
    override def enforcementCell(
        tools: ToolSet,
        autoApprove: AutoApprove,
        dispatch: TurnDispatch
    ): EnforcementCell =
      EnforcementCell(Enforcement.PromptOnly, "the prompt is the whole gate")

  // Pins that the interactive door hands its own listener to `runInteractive`:
  // without it the notice goes to the default no-op and every interactive turn
  // loses it silently.
  test("an interactive turn's enforcement notice reaches the caller"):
    val expected =
      "ClaudeCode cannot stop a ReadOnly turn from editing files or running " +
        "commands that change state — only the turn's own prompt asks it not to"
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val drivingInteraction =
      finishingInteraction("server-uuid-cccc", """{"value":5}""")
    supervised:
      val _ = new AgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = new PromptOnlyBackend,
        config = AgentConfig(tools = ToolSet.ReadOnly),
        prompts = DefaultPrompts,
        events = listener,
        interaction = drivingInteraction,
        agentName = "claude"
      ).interactive.run("anything")
      val caveats = seen.get().collect { case c: OrcaEvent.Caveat => c.message }
      assert(caveats.contains(expected), caveats)

  test("interactive StructuredResult names no agent"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val drivingInteraction =
      finishingInteraction("server-uuid-dddd", """{"value":4}""")
    supervised:
      val _ = new AgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = new PromptOnlyBackend,
        config = AgentConfig(),
        prompts = DefaultPrompts,
        events = e => { val _ = seen.updateAndGet(e :: _) },
        interaction = drivingInteraction,
        agentName = "claude"
      ).interactive.run("anything")
      assertEquals(
        seen.get().collect { case r: OrcaEvent.StructuredResult => r.agent },
        List(None)
      )

  test("interactive.runWithSession registers the (clientSid, serverSid) map"):
    // The framework must call `backend.sessions.register(session, result.wireId)`
    // after `interaction.drive` returns, so a follow-up turn on the same session
    // resumes the right thread.
    val clientSid =
      SessionId[BackendTag.ClaudeCode.type]("client-uuid-aaaa")
    val serverSid =
      WireSessionId[BackendTag.ClaudeCode.type]("server-uuid-bbbb")
    val backend = new SequencedBackend(List("""{"value":3}"""))
    val drivingInteraction =
      finishingInteraction(WireSessionId.value(serverSid), """{"value":3}""")
    supervised:
      val answer = new AgentCall[
        BackendTag.ClaudeCode.type,
        Answer
      ](
        backend = backend,
        config = AgentConfig(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = orca.events.OrcaListener.noop,
        interaction = drivingInteraction,
        agentName = "claude"
      ).interactive.runWithSession(
        "anything",
        clientSid,
        sessionKey = None
      )
      assertEquals(answer, Answer(3))
      assertEquals(
        backend.sessions.persistableWireId(clientSid),
        Some(serverSid),
        "framework must register the (client, server) mapping post-drain"
      )
