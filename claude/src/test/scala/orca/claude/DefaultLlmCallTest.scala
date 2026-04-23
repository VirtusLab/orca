package orca.claude

import orca.{
  Backend,
  InteractiveHandle,
  JsonData,
  LlmBackend,
  LlmConfig,
  LlmResult,
  SessionId,
  Usage,
  schemaFromJsonData,
  codecFromJsonData
}
import orca.io.DefaultPromptTemplate
import ox.supervised

import java.util.concurrent.atomic.AtomicReference

case class Answer(value: Int) derives JsonData

/** Fake backend that returns a pre-scripted sequence of outputs and records the
  * prompts it was asked to run. The session id is fixed — sessions aren't under
  * test here.
  */
class SequencedBackend(outputs: List[String])
    extends LlmBackend[Backend.ClaudeCode.type]:
  private val remaining: AtomicReference[List[String]] =
    AtomicReference(outputs)
  private val promptsRef: AtomicReference[List[String]] =
    AtomicReference(Nil)

  def prompts: List[String] = promptsRef.get().reverse

  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[Backend.ClaudeCode.type] =
    val _ = promptsRef.updateAndGet(prompt :: _)
    val next = remaining
      .getAndUpdate(rs => rs.drop(1))
      .headOption
      .getOrElse(throw new IllegalStateException("ran out of canned outputs"))
    LlmResult(
      sessionId = SessionId[Backend.ClaudeCode.type]("sess-test"),
      output = next,
      usage = Usage.empty
    )

  def prepareWorkspace(
      config: LlmConfig,
      outputSchema: String,
      workDir: os.Path
  )(using ox.Ox): Unit = ???
  def continueHeadless(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[Backend.ClaudeCode.type] = ???
  def runInteractive(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): InteractiveHandle[Backend.ClaudeCode.type] = ???
  def continueInteractive(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): InteractiveHandle[Backend.ClaudeCode.type] = ???

class DefaultLlmCallTest extends munit.FunSuite:

  import scala.concurrent.duration.DurationInt

  // Fast schedule so retry tests don't spend seconds sleeping between attempts.
  private val fastRetry =
    ox.scheduling.Schedule.fixedInterval(1.milli).maxRepeats(5)

  private def makeCall(backend: SequencedBackend): DefaultLlmCall[Answer] =
    new DefaultLlmCall[Answer](
      backend = backend,
      effectiveConfig = cfg => cfg.copy(retrySchedule = fastRetry),
      template = DefaultPromptTemplate,
      workDir = os.pwd,
      emit = _ => ()
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
      val answer = makeCall(backend).autonomous("what is the answer?")
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
      val answer = makeCall(backend).autonomous("a question")
      assertEquals(answer, Answer(7))
      assertEquals(backend.prompts.size, 1)
