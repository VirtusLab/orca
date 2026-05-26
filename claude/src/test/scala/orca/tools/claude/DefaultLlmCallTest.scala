package orca.tools.claude

import orca.llm.{BackendTag, JsonData, LlmConfig, SessionId}
import orca.events.{OrcaListener, Usage}

import orca.backend.{Interaction, LlmBackend, LlmResult}
import orca.llm.{DefaultLlmCall, DefaultPrompts}
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

case class Answer(value: Int) derives JsonData

/** Fake backend that returns a pre-scripted sequence of outputs and records the
  * prompts it was asked to run. The session id is fixed — sessions aren't under
  * test here.
  */
class SequencedBackend(outputs: List[String])
    extends LlmBackend[BackendTag.ClaudeCode.type]:
  private val remaining: AtomicReference[List[String]] =
    AtomicReference(outputs)
  private val promptsRef: AtomicReference[List[String]] =
    AtomicReference(Nil)
  private val seenEvents: AtomicReference[List[orca.events.OrcaListener]] =
    AtomicReference(Nil)
  private val registrations: AtomicReference[List[
    (SessionId[BackendTag.ClaudeCode.type], SessionId[BackendTag.ClaudeCode.type])
  ]] = AtomicReference(Nil)

  def prompts: List[String] = promptsRef.get().reverse

  /** Listeners the backend was called with, in invocation order. Lets tests
    * assert that `DefaultLlmCall` threaded its own `events` through rather
    * than silently dropping it on the floor.
    */
  def events: List[orca.events.OrcaListener] = seenEvents.get().reverse

  /** `(clientSid, serverSid)` pairs the framework passed to `registerSession`,
    * in invocation order. Lets tests assert that `DefaultLlmCall` wired the
    * post-drain hook through to the backend.
    */
  def registered: List[
    (SessionId[BackendTag.ClaudeCode.type], SessionId[BackendTag.ClaudeCode.type])
  ] = registrations.get().reverse

  override def registerSession(
      client: SessionId[BackendTag.ClaudeCode.type],
      server: SessionId[BackendTag.ClaudeCode.type]
  ): Unit =
    val _ = registrations.updateAndGet((client, server) :: _)

  def runAutonomous(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      config: LlmConfig,
      workDir: os.Path,
      events: orca.events.OrcaListener = orca.events.OrcaListener.noop
  ): LlmResult[BackendTag.ClaudeCode.type] =
    val _ = seenEvents.updateAndGet(events :: _)
    nextResult(prompt).copy(sessionId = session)

  def runInteractive(
      prompt: String,
      session: SessionId[BackendTag.ClaudeCode.type],
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): orca.backend.Conversation[BackendTag.ClaudeCode.type] =
    // Minimal stand-in: the conversation is not actually driven — the test's
    // `Interaction.drive` ignores it and returns a canned `LlmResult`. We
    // still need *something* to return so the interactive path compiles.
    new orca.backend.Conversation[BackendTag.ClaudeCode.type]:
      val outputSchema: Option[String] = None
      val events: Iterator[orca.backend.ConversationEvent] = Iterator.empty
      def awaitResult() = throw new UnsupportedOperationException("test stub")
      def sendUserMessage(text: String): Unit = ()
      def canAskUser: Boolean = false
      def cancel(): Unit = ()

  private def nextResult(
      prompt: String
  ): LlmResult[BackendTag.ClaudeCode.type] =
    val _ = promptsRef.updateAndGet(prompt :: _)
    val next = remaining
      .getAndUpdate(rs => rs.drop(1))
      .headOption
      .getOrElse(throw new IllegalStateException("ran out of canned outputs"))
    LlmResult(
      sessionId = SessionId[BackendTag.ClaudeCode.type]("sess-test"),
      output = next,
      usage = Usage.empty
    )

class DefaultLlmCallTest extends munit.FunSuite:

  import scala.concurrent.duration.DurationInt

  // Fast schedule so retry tests don't spend seconds sleeping between attempts.
  private val fastRetry =
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRetries(5)

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: orca.backend.Conversation[B]
    ): LlmResult[B] = throw new UnsupportedOperationException("test stub")

  private def makeCall(
      backend: SequencedBackend
  ): DefaultLlmCall[BackendTag.ClaudeCode.type, Answer] =
    new DefaultLlmCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
      prompts = DefaultPrompts,
      workDir = os.pwd,
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
    given orca.llm.Announce[Answer] =
      orca.llm.Announce.from(a => s"answer is ${a.value}")
    val backend = new SequencedBackend(List("""{"value":99}"""))
    val seen = AtomicReference[List[orca.events.OrcaEvent]](Nil)
    val call = new DefaultLlmCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
      prompts = DefaultPrompts,
      workDir = os.pwd,
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
    "autonomous threads its `events` listener to backend.runAutonomous"
  ):
    // Without this wiring, structured calls (which is what every reviewer
    // uses) lose tool-use / assistant-message visibility — the per-turn
    // events fire only when the backend gets the same listener the
    // DefaultLlmCall was constructed with.
    val backend = new SequencedBackend(List("""{"value":1}"""))
    val myListener: orca.events.OrcaListener = (_: orca.events.OrcaEvent) => ()
    supervised:
      val _ = new DefaultLlmCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        workDir = os.pwd,
        events = myListener,
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      assertEquals(backend.events, List(myListener))

  test(
    "autonomous emits StructuredResult with summary=None under default Announce"
  ):
    // The library's catch-all `Announce.default` returns an empty
    // string, which DefaultLlmCall normalises to `None` so listeners
    // can pattern-match without an empty-string sentinel.
    val backend = new SequencedBackend(List("""{"value":1}"""))
    val seen = AtomicReference[List[orca.events.OrcaEvent]](Nil)
    supervised:
      val _ = new DefaultLlmCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        workDir = os.pwd,
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
    "interactive.run registers (clientSid, serverSid) and returns the client id"
  ):
    // Pins the codex-interactive bug fix end-to-end: the framework must call
    // `backend.registerSession(session, result.sessionId)` after
    // `interaction.drive` returns, and restamp the returned id to the
    // caller-supplied `session` so a follow-up `.run(prompt, sid)` resumes
    // the right thread. Removing the `backend.registerSession` line in
    // `DefaultLlmCall.runInteractiveOnce` would fail this test.
    val clientSid =
      SessionId[BackendTag.ClaudeCode.type]("client-uuid-aaaa")
    val serverSid =
      SessionId[BackendTag.ClaudeCode.type]("server-uuid-bbbb")
    val backend = new SequencedBackend(List("""{"value":3}"""))
    val drivingInteraction: Interaction = new Interaction:
      val listeners: List[OrcaListener] = Nil
      def drive[B <: BackendTag](
          conversation: orca.backend.Conversation[B]
      ): LlmResult[B] =
        LlmResult[B](
          sessionId = SessionId[B](SessionId.value(serverSid)),
          output = """{"value":3}""",
          usage = Usage.empty
        )
    supervised:
      val (returned, answer) = new DefaultLlmCall[
        BackendTag.ClaudeCode.type,
        Answer
      ](
        backend = backend,
        effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        workDir = os.pwd,
        events = orca.events.OrcaListener.noop,
        interaction = drivingInteraction,
        agentName = "claude"
      ).interactive.run("anything", session = clientSid)
      assertEquals(answer, Answer(3))
      assertEquals(returned, clientSid, "returned id must be the caller's, not the server's")
      assertEquals(
        backend.registered,
        List((clientSid, serverSid)),
        "framework must register the (client, server) mapping post-drain"
      )
