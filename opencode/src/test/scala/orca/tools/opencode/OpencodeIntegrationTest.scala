package orca.tools.opencode

import orca.backend.SupervisedBackend
import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import orca.subprocess.OsProcCliRunner

/** End-to-end tests against a real `opencode serve`. Gated on the
  * `ORCA_INTEGRATION` environment variable, so `sbt test` without it behaves as
  * a pure unit suite. Requires `opencode` installed and on `PATH`, and a
  * configured provider (the pinned model below must be reachable — set creds
  * via the environment or `opencode auth`).
  *
  * Note: the per-invocation reaper that blocks launching `opencode serve`
  * inside some agent/sandbox command paths does not apply to a normal forked
  * test JVM.
  */
class OpencodeIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    3.minutes

  // A cheap, widely-available model; override via ORCA_OPENCODE_MODEL.
  private val model: Model =
    Model(sys.env.getOrElse("ORCA_OPENCODE_MODEL", "openai/gpt-4o-mini"))

  private val config: LlmConfig = LlmConfig.default.copy(model = Some(model))

  private def withBackend(body: OpencodeBackend => Unit): Unit =
    SupervisedBackend.using(OpencodeBackend(OsProcCliRunner))(body)

  private def fresh = SessionId.fresh[BackendTag.Opencode.type]

  test("headless prompt returns the requested literal output"):
    withBackend: backend =>
      val result = backend.runAutonomous(
        prompt = "Reply with the single word: READY. Nothing else.",
        session = fresh,
        config = config,
        workDir = os.temp.dir()
      )
      assert(
        result.output.toUpperCase.contains("READY"),
        s"expected READY, got: ${result.output}"
      )
      assert(SessionId.value(result.sessionId).nonEmpty)

  test("structured output returns the validated object"):
    withBackend: backend =>
      val result = backend.runAutonomous(
        prompt = "Extract: Anthropic was founded in 2021.",
        session = fresh,
        config = config,
        workDir = os.temp.dir(),
        outputSchema = Some(
          """{"type":"object","properties":{"company":{"type":"string"},"founded":{"type":"number"}},"required":["company","founded"],"additionalProperties":false}"""
        )
      )
      assert(result.output.contains("Anthropic"), result.output)
      assert(result.output.contains("2021"), result.output)

  test("a resumed session recalls earlier context"):
    withBackend: backend =>
      val workDir = os.temp.dir()
      val session = fresh
      val _ = backend.runAutonomous(
        prompt = "Remember the number 42. Reply with: stored.",
        session = session,
        config = config,
        workDir = workDir
      )
      val second = backend.runAutonomous(
        prompt =
          "What number did I ask you to remember? Reply with just the number.",
        session = session,
        config = config,
        workDir = workDir
      )
      assert(
        second.output.contains("42"),
        s"expected 42, got: ${second.output}"
      )

  test("read-only turn cannot write a file"):
    withBackend: backend =>
      val workDir = os.temp.dir()
      val _ = backend.runAutonomous(
        prompt = "Create a file named marker.txt containing the word hello.",
        session = fresh,
        config = config.copy(readOnly = true),
        workDir = workDir
      )
      assert(
        !os.exists(workDir / "marker.txt"),
        "read-only turn should not have created marker.txt"
      )
