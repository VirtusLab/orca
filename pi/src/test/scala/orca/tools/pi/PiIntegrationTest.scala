package orca.tools.pi

import orca.agents.{BackendTag, AgentConfig, SessionId, ToolSet}
import orca.subprocess.OsProcCliRunner

/** End-to-end smoke test against the real `pi` CLI. Gated on `ORCA_INTEGRATION`
  * so normal unit test runs do not require Pi to be installed or authenticated.
  */
class PiIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    2.minutes

  private def fresh: SessionId[BackendTag.Pi.type] =
    SessionId.fresh[BackendTag.Pi.type]

  test("RPC autonomous prompt returns requested literal output"):
    val backend = new PiBackend(OsProcCliRunner)
    val result = backend.runAutonomous(
      prompt = "Reply with the single word: READY",
      session = fresh,
      config = AgentConfig.default.copy(tools = ToolSet.ReadOnly),
      workDir = os.temp.dir()
    )
    assert(
      result.output.contains("READY"),
      s"expected output to contain READY, got: ${result.output}"
    )
