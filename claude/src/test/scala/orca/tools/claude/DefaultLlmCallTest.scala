package orca.tools.claude

import orca.{BackendTag, JsonData, LlmConfig, OrcaListener, SessionId, Usage}
import orca.backend.{Interaction, LlmBackend, LlmResult}
import orca.io.{DefaultLlmCall, DefaultPrompts}
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

  def prompts: List[String] = promptsRef.get().reverse

  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[BackendTag.ClaudeCode.type] = nextResult(prompt)

  /** Record a continuation call tagged with its sessionId so tests can assert
    * the same session is being resumed across retries.
    */
  def continueHeadless(
      sessionId: SessionId[BackendTag.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[BackendTag.ClaudeCode.type] =
    nextResult(prompt).copy(sessionId = sessionId)

  def runInteractive(
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): orca.backend.Conversation[BackendTag.ClaudeCode.type] = ???
  def continueInteractive(
      sessionId: SessionId[BackendTag.ClaudeCode.type],
      prompt: String,
      displayPrompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): orca.backend.Conversation[BackendTag.ClaudeCode.type] = ???

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
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRepeats(5)

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
      events = orca.OrcaListener.noop,
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
    "continueSession retries against the same sessionId on parse failure"
  ):
    val backend = new SequencedBackend(
      List("garbage", """{"value":11}""")
    )
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-under-test")
    supervised:
      val answer =
        makeCall(backend).autonomous.continueSession(sid, "next step")
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
    given orca.Announce[Answer] =
      orca.Announce.from(a => s"answer is ${a.value}")
    val backend = new SequencedBackend(List("""{"value":99}"""))
    val seen = AtomicReference[List[orca.OrcaEvent]](Nil)
    val call = new DefaultLlmCall[BackendTag.ClaudeCode.type, Answer](
      backend = backend,
      effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
      prompts = DefaultPrompts,
      workDir = os.pwd,
      events = (e: orca.OrcaEvent) => { val _ = seen.updateAndGet(e :: _) },
      interaction = stubInteraction,
      agentName = "claude"
    )
    supervised:
      val _ = call.autonomous.run("anything")
      val structured = seen.get().collect {
        case orca.OrcaEvent.StructuredResult(raw, summary) => (raw, summary)
      }
      assertEquals(structured, List(("""{"value":99}""", Some("answer is 99"))))

  test(
    "autonomous emits StructuredResult with summary=None under default Announce"
  ):
    // The library's catch-all `Announce.default` returns an empty
    // string, which DefaultLlmCall normalises to `None` so listeners
    // can pattern-match without an empty-string sentinel.
    val backend = new SequencedBackend(List("""{"value":1}"""))
    val seen = AtomicReference[List[orca.OrcaEvent]](Nil)
    supervised:
      val _ = new DefaultLlmCall[BackendTag.ClaudeCode.type, Answer](
        backend = backend,
        effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
        prompts = DefaultPrompts,
        workDir = os.pwd,
        events = (e: orca.OrcaEvent) => { val _ = seen.updateAndGet(e :: _) },
        interaction = stubInteraction,
        agentName = "claude"
      ).autonomous.run("anything")
      val structured = seen.get().collect {
        case orca.OrcaEvent.StructuredResult(raw, summary) => (raw, summary)
      }
      assertEquals(structured, List(("""{"value":1}""", None)))
