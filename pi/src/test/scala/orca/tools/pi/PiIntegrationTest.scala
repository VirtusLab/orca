package orca.tools.pi

import orca.agents.{BackendTag, AgentConfig, SessionId, ToolSet, onWire}
import orca.subprocess.OsProcCliRunner
import orca.testkit.TempDirs

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
    val backend = new PiBackend(OsProcCliRunner, workDir = TempDirs.dir())
    val result = backend.runAutonomous(
      prompt = "Reply with the single word: READY",
      session = fresh,
      config = AgentConfig().copy(tools = ToolSet.ReadOnly)
    )
    assert(
      result.output.contains("READY"),
      s"expected output to contain READY, got: ${result.output}"
    )

  test("a session dir written by one backend is resumable by the next"):
    val workDir = TempDirs.dir()
    val session = fresh
    val config = AgentConfig().copy(tools = ToolSet.ReadOnly)

    val _ = new PiBackend(OsProcCliRunner, workDir = workDir).runAutonomous(
      prompt = "Remember the word BANANA. Reply with the single word: OK",
      session = session,
      config = config
    )

    // A second instance stands in for the next orca run: the wire id comes back
    // from the run manifest, and only Pi's on-disk session dir carries context.
    val next = new PiBackend(OsProcCliRunner, workDir = workDir)
    next.sessions.register(session, session.onWire)
    assert(next.sessions.willContinue(session))

    val resumed = next.runAutonomous(
      prompt =
        "Which word did I ask you to remember? Reply with just that word.",
      session = session,
      config = config
    )
    assert(
      resumed.output.contains("BANANA"),
      s"expected the resumed turn to recall BANANA, got: ${resumed.output}"
    )
