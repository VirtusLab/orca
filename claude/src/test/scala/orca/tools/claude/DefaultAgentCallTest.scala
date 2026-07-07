package orca.tools.claude

import orca.{AgentTurnFailed, OrcaFlowException}
import orca.agents.{
  BackendTag,
  JsonData,
  AgentConfig,
  SessionId,
  WireSessionId,
  onWire
}
import orca.events.{OrcaListener, Usage}

import orca.backend.{
  Interaction,
  AgentBackend,
  AgentResult,
  Dispatch,
  SessionRegistry,
  SessionSupport
}
import orca.agents.{DefaultAgentCall, DefaultPrompts}
import ox.supervised

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

case class Answer(value: Int) derives JsonData

/** Fake backend that returns a pre-scripted sequence of outputs and records the
  * prompts it was asked to run. The session id is fixed — sessions aren't under
  * test here.
  */
class SequencedBackend(outputs: List[String])
    extends AgentBackend[BackendTag.ClaudeCode.type]:
  private val remaining: AtomicReference[List[String]] =
    AtomicReference(outputs)
  private val promptsRef: AtomicReference[List[String]] =
    AtomicReference(Nil)
  private val seenEvents: AtomicReference[List[orca.events.OrcaListener]] =
    AtomicReference(Nil)
  private val seenSchemas: AtomicReference[List[Option[String]]] =
    AtomicReference(Nil)
  private val registrations: AtomicReference[List[
    (
        SessionId[BackendTag.ClaudeCode.type],
        WireSessionId[BackendTag.ClaudeCode.type]
    )
  ]] = AtomicReference(Nil)

  def prompts: List[String] = promptsRef.get().reverse

  /** Listeners the backend was called with, in invocation order. Lets tests
    * assert that `DefaultAgentCall` threaded its own `events` through rather
    * than silently dropping it on the floor.
    */
  def events: List[orca.events.OrcaListener] = seenEvents.get().reverse

  /** `outputSchema` values the backend received, in invocation order. Lets
    * tests assert that `DefaultAgentCall` actually passes `Some(<schema>)`
    * rather than dropping to `None`.
    */
  def schemas: List[Option[String]] = seenSchemas.get().reverse

  /** `(clientSid, serverSid)` pairs the framework passed to
    * `sessions.register`, in invocation order. Lets tests assert that
    * `DefaultAgentCall` wired the post-drain hook through to the backend.
    */
  def registered: List[
    (
        SessionId[BackendTag.ClaudeCode.type],
        WireSessionId[BackendTag.ClaudeCode.type]
    )
  ] = registrations.get().reverse

  /** Records every `register` the framework routes through the durability
    * capability (`AgentCall.runInteractiveOnce` → `backend.sessions.register`).
    */
  val sessions: SessionSupport[BackendTag.ClaudeCode.type] =
    SessionSupport.Ephemeral(
      new SessionRegistry[BackendTag.ClaudeCode.type]:
        def dispatchFor(
            client: SessionId[BackendTag.ClaudeCode.type]
        ): Dispatch[BackendTag.ClaudeCode.type] =
          Dispatch.Fresh(Some(client.onWire))
        def commitSuccess(
            client: SessionId[BackendTag.ClaudeCode.type],
            server: WireSessionId[BackendTag.ClaudeCode.type]
        ): Unit =
          val _ = registrations.updateAndGet((client, server) :: _)
        def resumeWireId(
            client: SessionId[BackendTag.ClaudeCode.type]
        ): Option[WireSessionId[BackendTag.ClaudeCode.type]] = None
    )

  val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode
  val workDir: os.Path = os.pwd

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: AgentConfig,
      events: orca.events.OrcaListener,
      outputSchema: Option[String]
  ): AgentResult[BackendTag.ClaudeCode.type] =
    val _ = seenEvents.updateAndGet(events :: _)
    val _ = seenSchemas.updateAndGet(outputSchema :: _)
    nextResult(prompt)

  def runInteractive(
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
      val events: Iterator[orca.backend.ConversationEvent] = Iterator.empty
      def awaitResult() = throw new UnsupportedOperationException("test stub")
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

  // LLM `run` is now gated on `InStage`; mint the token once for the suite
  // (package `orca.tools.claude` can reach `InStage.unsafe`).
  private given orca.InStage = orca.InStage.unsafe

  import scala.concurrent.duration.DurationInt

  // Fast schedule so retry tests don't spend seconds sleeping between attempts.
  private val fastRetry =
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRetries(5)

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: orca.backend.Conversation[B]
    ): AgentResult[B] = throw new UnsupportedOperationException("test stub")

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
      val (_, answer) = makeCall(backend).autonomous.run("what is the answer?")
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
      val (_, answer) = makeCall(backend).autonomous.run("a question")
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
      val (_, answer) =
        makeCall(backend).autonomous.run("next step", session = sid)
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
    "autonomous forwards a Some(schema) to backend.runAutonomous"
  ):
    // Pins the SPI-level wiring: structured calls must carry their
    // generated schema down to the backend so the conversation knows it's
    // in structured mode (drain suppresses raw JSON) and the CLI gets
    // `--json-schema`/`--output-schema`. A regression that dropped the
    // schema to None would compile and pass every other test.
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
    val myListener: orca.events.OrcaListener = (_: orca.events.OrcaEvent) => ()
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
      assertEquals(backend.events, List(myListener))

  test(
    "autonomous emits StructuredResult with summary=None under default Announce"
  ):
    // The library's catch-all `Announce.default` returns an empty
    // string, which DefaultAgentCall normalises to `None` so listeners
    // can pattern-match without an empty-string sentinel.
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
      override def runAutonomous(
          prompt: String,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: AgentConfig,
          events: OrcaListener,
          outputSchema: Option[String]
      ): AgentResult[BackendTag.ClaudeCode.type] =
        val _ = calls.incrementAndGet()
        throw new AgentTurnFailed("Prompt is too long")
    supervised:
      val ex = intercept[AgentTurnFailed]:
        makeCall(backend).autonomous.run("the original input")
      assertEquals(calls.get(), 1, "AgentTurnFailed must not be retried")
      assert(ex.getMessage.contains("agent 'claude'"), ex.getMessage)
      assert(ex.getMessage.contains("Prompt is too long"), ex.getMessage)

  test(
    "autonomous still retries a non-AgentTurnFailed backend failure"
  ):
    // A pre-spawn open failure (transient broken pipe) is retryable — the
    // session was never registered. Pins that the AgentTurnFailed carve-out
    // didn't disable transient-failure retries.
    val calls = new AtomicInteger(0)
    val backend = new SequencedBackend(List("""{"value":8}""")):
      override def runAutonomous(
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
          super.runAutonomous(
            prompt,
            session,
            config,
            events,
            outputSchema
          )
    supervised:
      val (_, answer) = makeCall(backend).autonomous.run("q")
      assertEquals(answer, Answer(8))
      assertEquals(calls.get(), 2, "transient failure should be retried once")

  test(
    "autonomous passes the effective (tool-resolved) config to prompts.autonomous"
  ):
    // Pins the prompt-resolved-config fix: the prompt builder must see the
    // EFFECTIVE config (tool defaults folded in), not the raw/empty per-call
    // config. Here the tool-level config carries systemPrompt =
    // Some("tool-prompt") via effectiveConfig; the call omits config, so the
    // old code (which handed the raw None-derived config to prompts.autonomous)
    // would capture None here.
    val captured = new AtomicReference[Option[AgentConfig]](None)
    val recordingPrompts = new orca.agents.Prompts:
      def autonomous(
          input: String,
          outputSchema: String,
          config: AgentConfig
      ): String =
        val _ = captured.set(Some(config))
        DefaultPrompts.autonomous(input, outputSchema, config)
      def interactive(
          input: String,
          outputSchema: String,
          config: AgentConfig
      ): String = DefaultPrompts.interactive(input, outputSchema, config)
      def retry(failedResponse: String, parseError: String): String =
        DefaultPrompts.retry(failedResponse, parseError)
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

  test(
    "interactive.run registers (clientSid, serverSid) and returns the client id"
  ):
    // Pins the codex-interactive bug fix end-to-end: the framework must call
    // `backend.sessions.register(session, result.wireId)` after
    // `interaction.drive` returns, and return the caller-supplied `session`
    // so a follow-up `.run(prompt, sid)` resumes the right thread. Removing
    // the `backend.sessions.register` call in
    // `DefaultAgentCall.runInteractiveOnce` would fail this test.
    val clientSid =
      SessionId[BackendTag.ClaudeCode.type]("client-uuid-aaaa")
    val serverSid =
      WireSessionId[BackendTag.ClaudeCode.type]("server-uuid-bbbb")
    val backend = new SequencedBackend(List("""{"value":3}"""))
    val drivingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.Conversation[B]
      ): AgentResult[B] =
        AgentResult[B](
          wireId = WireSessionId[B](WireSessionId.value(serverSid)),
          output = """{"value":3}""",
          usage = Usage.empty
        )
    supervised:
      val (returned, answer) = new DefaultAgentCall[
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
      ).interactive.run("anything", session = clientSid)
      assertEquals(answer, Answer(3))
      assertEquals(
        returned,
        clientSid,
        "returned id must be the caller's, not the server's"
      )
      assertEquals(
        backend.registered,
        List((clientSid, serverSid)),
        "framework must register the (client, server) mapping post-drain"
      )
