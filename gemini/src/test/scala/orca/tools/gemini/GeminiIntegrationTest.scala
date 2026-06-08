package orca.tools.gemini

import orca.llm.{AutoApprove, BackendTag, LlmConfig, Model, SessionId}
import orca.backend.SupervisedBackend
import orca.subprocess.OsProcCliRunner

/** End-to-end tests against the real `gemini` CLI. Gated on the
  * `ORCA_INTEGRATION` environment variable so `sbt test` without the flag
  * behaves like a pure unit suite. Requires `gemini` to be installed and
  * authenticated on the host (e.g. `GEMINI_API_KEY` or an OAuth login).
  *
  * The tests use [[AutoApprove.All]] (i.e. `--approval-mode yolo`) so a
  * headless turn that touches tools doesn't block on an approval prompt no one
  * can answer.
  */
class GeminiIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    3.minutes

  private def withBackend(body: GeminiBackend => Unit): Unit =
    SupervisedBackend.using(new GeminiBackend(OsProcCliRunner))(body)

  // A cheap, widely-available model; override via ORCA_GEMINI_MODEL.
  private val model: Model =
    Model(sys.env.getOrElse("ORCA_GEMINI_MODEL", "gemini-2.5-flash"))

  private val unsandboxed: LlmConfig =
    LlmConfig.default.copy(autoApprove = AutoApprove.All, model = Some(model))

  private def fresh = SessionId.fresh[BackendTag.Gemini.type]

  test("headless prompt returns the requested literal output"):
    withBackend: backend =>
      val result = backend.runAutonomous(
        prompt =
          "Reply with the single word: READY. Reply with that word and nothing else.",
        session = fresh,
        config = unsandboxed,
        workDir = os.temp.dir()
      )
      assert(
        result.output.toUpperCase.contains("READY"),
        s"expected output to contain READY, got: ${result.output}"
      )
      assert(SessionId.value(result.sessionId).nonEmpty)

  test("a resumed call carries conversational context across turns"):
    withBackend: backend =>
      val workDir = os.temp.dir()
      val session = fresh
      val _ = backend.runAutonomous(
        prompt = "Remember the number 42. Reply with the single word: stored.",
        session = session,
        config = unsandboxed,
        workDir = workDir
      )
      val second = backend.runAutonomous(
        prompt =
          "What number did I ask you to remember? Reply with just the number.",
        session = session,
        config = unsandboxed,
        workDir = workDir
      )
      assert(
        second.output.contains("42"),
        s"expected the resumed turn to recall 42, got: ${second.output}"
      )
