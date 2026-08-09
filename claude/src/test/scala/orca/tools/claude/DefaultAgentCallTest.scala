package orca.tools.claude

import orca.testkit.StubEnforcementCell
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
import orca.events.{OrcaEvent, OrcaListener, TurnDebit, Usage}
import orca.testkit.Usages.usage

import orca.backend.{
  Interaction,
  AgentBackend,
  AgentResult,
  IdScheme,
  SessionSupport
}
import orca.agents.{DefaultAgentCall, DefaultPrompts}
import ox.supervised

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

case class Answer(value: Int) derives JsonData

/** Fake backend that returns a pre-scripted sequence of outputs and records the
  * prompts it was asked to run. The session id is fixed — sessions aren't under
  * test here. `mode` is the declared [[StructuredOutputMode]]; defaults to the
  * text contract, overridden by the test pinning the Tool-mode instruction.
  */
class SequencedBackend(
    outputs: List[String],
    mode: StructuredOutputMode = StructuredOutputMode.RawText
) extends AgentBackend[BackendTag.ClaudeCode.type]
    with StubEnforcementCell[BackendTag.ClaudeCode.type]:
  private val remaining: AtomicReference[List[String]] =
    AtomicReference(outputs)
  private val promptsRef: AtomicReference[List[String]] =
    AtomicReference(Nil)
  private val seenEvents: AtomicReference[List[orca.events.OrcaListener]] =
    AtomicReference(Nil)
  private val seenSchemas: AtomicReference[List[Option[String]]] =
    AtomicReference(Nil)
  def prompts: List[String] = promptsRef.get().reverse

  /** Listeners the backend was called with, in invocation order. Lets tests
    * assert that `DefaultAgentCall` threaded its own `events` through rather
    * than dropping it.
    */
  def events: List[orca.events.OrcaListener] = seenEvents.get().reverse

  /** `outputSchema` values the backend received, in invocation order. Lets
    * tests assert that `DefaultAgentCall` actually passes `Some(<schema>)`
    * rather than dropping to `None`.
    */
  def schemas: List[Option[String]] = seenSchemas.get().reverse

  /** A real capability: tests observe the mapping the framework registered
    * (`AgentCall.runInteractiveOnce` → `backend.sessions.register`) via
    * `sessions.persistableWireId`.
    */
  val sessions: SessionSupport[BackendTag.ClaudeCode.type] =
    SessionSupport.durable(IdScheme.ServerMinted, _ => false)

  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode
  val workDir: os.Path = os.pwd
  def structuredOutputMode: StructuredOutputMode = mode

  protected def doRunAutonomous(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      events: orca.events.OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.ClaudeCode.type] =
    val _ = seenEvents.updateAndGet(events :: _)
    val _ = seenSchemas.updateAndGet(outputSchema :: _)
    nextResult(prompt)

  protected def doRunInteractive(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using ox.Ox): orca.backend.Conversation[BackendTag.ClaudeCode.type] =
    // Minimal stand-in: the conversation is not actually driven — the test's
    // `Interaction.drive` ignores it and returns a canned `AgentResult`. We
    // still need *something* to return so the interactive path compiles.
    new orca.backend.Conversation[BackendTag.ClaudeCode.type]:
      val outputSchema: Option[String] = None
      def events(using ox.Ox): Iterator[orca.backend.ConversationEvent] =
        Iterator.empty
      def awaitResult()(using ox.Ox) =
        throw new UnsupportedOperationException("test stub")
      def canAskUser: Boolean = false
      def cancel(): Unit = ()

  private def nextResult(
      prompt: String
  ): AgentResult[BackendTag.ClaudeCode.type] =
    val _ = promptsRef.updateAndGet(prompt :: _)
    val next = remaining
      .getAndUpdate(rs => rs.drop(1))
      .headOption
      .getOrElse(throw new IllegalStateException("ran out of canned outputs"))
    AgentResult(
      wireId = WireSessionId[BackendTag.ClaudeCode.type]("sess-test"),
      output = next,
      usage = Usage.empty
    )

class DefaultAgentCallTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token once for the suite.
  private given orca.InStage = orca.InStage.unsafe

  import scala.concurrent.duration.DurationInt

  // Fast schedule so retry tests don't spend seconds sleeping between attempts.
  private val fastRetry =
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRetries(5)

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: orca.backend.Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  private def makeCall(
      backend: SequencedBackend
  ): DefaultAgentCall[BackendTag.ClaudeCode.type, Answer] =
    new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      effectiveConfig = cfg =>
        cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
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
          sessionName = None,
          config = None,
          emitPrompt = true
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
    val call = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      effectiveConfig =
        cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
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
        case orca.events.OrcaEvent.StructuredResult(raw, summary) =>
          (raw, summary)
      }
      assertEquals(structured, List(("""{"value":99}""", Some("answer is 99"))))

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
    // DefaultAgentCall was constructed with.
    val backend = new SequencedBackend(List("""{"value":1}"""))
    val received =
      new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val myListener: OrcaListener = e => {
      val _ = received.updateAndGet(e :: _)
    }
    supervised:
      val _ = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = myListener,
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      // The backend is handed an agent-attributing wrapper, so identity would
      // say nothing; what has to hold is that its events still arrive.
      assertEquals(backend.events.size, 1)
      received.set(Nil)
      backend.events.foreach(_.onEvent(OrcaEvent.Step("probe")))
      assertEquals(received.get(), List(OrcaEvent.Step("probe")))

  test(
    "autonomous emits StructuredResult with summary=None under default Announce"
  ):
    // No specific Announce[Answer] in scope, so the catch-all
    // `Announce.default` resolves — the emission maps that to `None`, the
    // "no instance wired" arm of the tri-state summary: renderers fall back
    // to showing the raw payload so the result stays visible.
    val backend = new SequencedBackend(List("""{"value":1}"""))
    val seen = AtomicReference[List[orca.events.OrcaEvent]](Nil)
    supervised:
      val _ = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = (e: orca.events.OrcaEvent) => {
          val _ = seen.updateAndGet(e :: _)
        },
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      val structured = seen.get().collect {
        case orca.events.OrcaEvent.StructuredResult(raw, summary) =>
          (raw, summary)
      }
      assertEquals(structured, List(("""{"value":1}""", None)))

  test(
    "autonomous emits StructuredResult with summary=Some(\"\") for a deliberately-silent Announce"
  ):
    // A specific Announce that returns no message (Announce.from(_ => ""),
    // like ReviewResult's) is DELIBERATE silence — distinct from the
    // no-instance `None` above: renderers show nothing rather than falling
    // back to the raw payload.
    given orca.agents.Announce[Answer] = orca.agents.Announce.from(_ => "")
    val backend = new SequencedBackend(List("""{"value":7}"""))
    val seen = AtomicReference[List[orca.events.OrcaEvent]](Nil)
    supervised:
      val _ = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = (e: orca.events.OrcaEvent) => {
          val _ = seen.updateAndGet(e :: _)
        },
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      val structured = seen.get().collect {
        case orca.events.OrcaEvent.StructuredResult(raw, summary) =>
          (raw, summary)
      }
      assertEquals(structured, List(("""{"value":7}""", Some(""))))

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
      val _ = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
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
      override protected def doRunAutonomous(
          prompt: String,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: AgentConfig,
          events: OrcaListener,
          outputSchema: Option[String]
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
  // emitted: an all-zero TokensUsed would read as a turn measured at zero.
  test("an Unobserved debit emits no TokensUsed"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val backend = new SequencedBackend(Nil):
      override protected def doRunAutonomous(
          prompt: String,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: AgentConfig,
          events: OrcaListener,
          outputSchema: Option[String]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        throw new AgentTurnFailed("no usage on the wire", TurnDebit.Unobserved)
    supervised:
      val _ = intercept[AgentTurnFailed]:
        new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = backend,
          effectiveConfig = cfg => cfg.getOrElse(AgentConfig()),
          prompts = DefaultPrompts,
          events = listener,
          interaction = stubInteraction,
          agentName = "claude"
        ).autonomous.run("anything")
      assertEquals(
        seen.get().collect { case t: OrcaEvent.TokensUsed => t },
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
      override protected def doRunAutonomous(
          prompt: String,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: AgentConfig,
          events: OrcaListener,
          outputSchema: Option[String]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        if calls.incrementAndGet() == 1 then
          super.doRunAutonomous(prompt, session, config, events, outputSchema)
        else
          throw new AgentTurnFailed(
            "provider error",
            TurnDebit.Observed(spent, None)
          )
    supervised:
      val _ = intercept[AgentTurnFailed]:
        new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = backend,
          effectiveConfig =
            cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
          prompts = DefaultPrompts,
          events = listener,
          interaction = stubInteraction,
          agentName = "claude"
        ).autonomous.run("anything")
      assertEquals(
        seen.get().reverse.collect { case t: OrcaEvent.TokensUsed =>
          t.attempt
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
      override protected def doRunAutonomous(
          prompt: String,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: AgentConfig,
          events: OrcaListener,
          outputSchema: Option[String]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        if calls.getAndIncrement() == 0 then
          throw new OrcaFlowException(
            "Failed to open claude stream-json session: Broken pipe"
          )
        else
          super.doRunAutonomous(
            prompt,
            session,
            config,
            events,
            outputSchema
          )
    supervised:
      val answer = makeCall(backend).autonomous.run("q")
      assertEquals(answer, Answer(8))
      assertEquals(calls.get(), 2, "transient failure should be retried once")

  test(
    "autonomous passes the effective (tool-resolved) config to prompts.autonomous"
  ):
    // The prompt builder must see the EFFECTIVE config (tool defaults folded
    // in), not the raw/empty per-call config. Here the tool-level config carries
    // systemPrompt = Some("tool-prompt") via effectiveConfig, and the call omits
    // config, so a builder given the raw None-derived config would capture None.
    val captured = new AtomicReference[Option[AgentConfig]](None)
    val recordingPrompts = new orca.agents.Prompts:
      def autonomous(
          input: String,
          outputSchema: String,
          config: AgentConfig,
          mode: StructuredOutputMode
      ): String =
        val _ = captured.set(Some(config))
        DefaultPrompts.autonomous(input, outputSchema, config, mode)
      def interactive(
          input: String,
          outputSchema: String,
          config: AgentConfig
      ): String = DefaultPrompts.interactive(input, outputSchema, config)
      def retry(
          failedResponse: String,
          parseError: String,
          mode: StructuredOutputMode
      ): String = DefaultPrompts.retry(failedResponse, parseError, mode)
    val backend = new SequencedBackend(List("""{"value":1}"""))
    supervised:
      val _ = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig = cfg =>
          cfg
            .getOrElse(AgentConfig())
            .copy(
              systemPrompt = Some("tool-prompt"),
              retrySchedule = fastRetry
            ),
        prompts = recordingPrompts,
        events = orca.events.OrcaListener.noop,
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      assertEquals(captured.get().flatMap(_.systemPrompt), Some("tool-prompt"))

  // Ctrl-C at an interactive prompt abandons a turn the user is still billed
  // for; the conversation carries what it spent on the cancellation itself.
  test("an interactive turn cancelled after the model ran emits TokensUsed"):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val spent = usage(90L, 4L)
    val cancellingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.Conversation[B]
      )(using ox.Ox): AgentResult[B] =
        throw new OrcaInteractiveCancelled(
          TurnDebit.Observed(spent, Some(Model("claude-sonnet-5")))
        )
    supervised:
      val _ = intercept[OrcaInteractiveCancelled]:
        new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = new SequencedBackend(Nil),
          effectiveConfig = cfg => cfg.getOrElse(AgentConfig()),
          prompts = DefaultPrompts,
          events = listener,
          interaction = cancellingInteraction,
          agentName = "claude"
        ).interactive.run("anything")
      assertEquals(
        seen.get().collect { case t: OrcaEvent.TokensUsed => t.usage },
        List(spent)
      )

  // The autonomous path emits a failed turn's debit; the interactive one runs
  // the same models against the same bills and used to report nothing.
  test(
    "an interactive turn failing after the model ran still emits TokensUsed"
  ):
    val seen = new AtomicReference[List[OrcaEvent]](Nil)
    val listener: OrcaListener = e => { val _ = seen.updateAndGet(e :: _) }
    val spent = usage(120L, 8L, Some(BigDecimal("0.0031")))
    val failingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.Conversation[B]
      )(using ox.Ox): AgentResult[B] =
        throw new AgentTurnFailed(
          "provider error",
          TurnDebit.Observed(spent, Some(Model("claude-sonnet-5")))
        )
    supervised:
      val _ = intercept[AgentTurnFailed]:
        new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
          backend = new SequencedBackend(Nil),
          effectiveConfig = cfg => cfg.getOrElse(AgentConfig()),
          prompts = DefaultPrompts,
          events = listener,
          interaction = failingInteraction,
          agentName = "claude"
        ).interactive.run("anything")
      assertEquals(
        seen.get().collect { case t: OrcaEvent.TokensUsed =>
          (t.usage, t.model)
        },
        List((spent, Some(Model("claude-sonnet-5"))))
      )

  /** [[SequencedBackend]] whose gate is prompt-deep, so a read-only turn has a
    * shortfall to report.
    */
  private class PromptOnlyBackend extends SequencedBackend(Nil):
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
    val drivingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.Conversation[B]
      )(using ox.Ox): AgentResult[B] =
        AgentResult[B](
          wireId = WireSessionId[B]("server-uuid-cccc"),
          output = """{"value":5}""",
          usage = Usage.empty
        )
    supervised:
      val _ = new DefaultAgentCall[BackendTag.ClaudeCode.type, Answer](
        backend = new PromptOnlyBackend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(tools = ToolSet.ReadOnly),
        prompts = DefaultPrompts,
        events = listener,
        interaction = drivingInteraction,
        agentName = "claude"
      ).interactive.run("anything")
      val steps = seen.get().collect { case s: OrcaEvent.Step => s.message }
      assert(steps.contains(expected), steps)

  test("interactive.runWithSession registers the (clientSid, serverSid) map"):
    // The framework must call `backend.sessions.register(session, result.wireId)`
    // after `interaction.drive` returns, so a follow-up turn on the same session
    // resumes the right thread.
    val clientSid =
      SessionId[BackendTag.ClaudeCode.type]("client-uuid-aaaa")
    val serverSid =
      WireSessionId[BackendTag.ClaudeCode.type]("server-uuid-bbbb")
    val backend = new SequencedBackend(List("""{"value":3}"""))
    val drivingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.Conversation[B]
      )(using ox.Ox): AgentResult[B] =
        AgentResult[B](
          wireId = WireSessionId[B](WireSessionId.value(serverSid)),
          output = """{"value":3}""",
          usage = Usage.empty
        )
    supervised:
      val answer = new DefaultAgentCall[
        BackendTag.ClaudeCode.type,
        Answer
      ](
        backend = backend,
        effectiveConfig =
          cfg => cfg.getOrElse(AgentConfig()).copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        events = orca.events.OrcaListener.noop,
        interaction = drivingInteraction,
        agentName = "claude"
      ).interactive.runWithSession(
        "anything",
        clientSid,
        sessionName = None,
        config = None
      )
      assertEquals(answer, Answer(3))
      assertEquals(
        backend.sessions.persistableWireId(clientSid),
        Some(serverSid),
        "framework must register the (client, server) mapping post-drain"
      )
