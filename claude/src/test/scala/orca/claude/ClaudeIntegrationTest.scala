package orca.claude

import orca.*

import scala.util.Using

/** End-to-end tests against the real `claude` CLI. Gated on the
  * `ORCA_INTEGRATION` environment variable so `sbt test` without the flag
  * behaves like a pure unit suite. Require `claude` to be installed and
  * authenticated on the host.
  */
class ClaudeIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  private val backend = new ClaudeBackend(OsProcCliRunner)

  test("headless prompt returns the requested literal output"):
    val result = backend.runHeadless(
      prompt = "Reply with the single word: READY",
      config = LlmConfig.default,
      workDir = os.temp.dir()
    )
    assert(
      result.output.contains("READY"),
      s"expected output to contain READY, got: ${result.output}"
    )
    assert(SessionId.value(result.sessionId).nonEmpty)

  test("continueHeadless carries conversational context across turns"):
    val workDir = os.temp.dir()
    val first = backend.runHeadless(
      prompt = "Remember the number 42. Reply with: stored.",
      config = LlmConfig.default,
      workDir = workDir
    )
    val second = backend.continueHeadless(
      sessionId = first.sessionId,
      prompt = "What number did I ask you to remember?",
      config = LlmConfig.default,
      workDir = workDir
    )
    assert(
      second.output.contains("42"),
      s"expected resumed session to recall '42', got: ${second.output}"
    )

  test("stream-json output parses into LlmOutput events"):
    val proc = os
      .proc(
        "claude",
        "-p",
        "Say hi.",
        "--output-format",
        "stream-json",
        "--verbose"
      )
      .spawn(
        cwd = os.temp.dir(),
        stdout = os.Pipe,
        stderr = os.Inherit
      )
    try
      val events = Using.resource(
        scala.io.Source.fromInputStream(proc.stdout.wrapped)
      ) { source =>
        ClaudeNdjsonParser.parseLines(source.getLines()).toList
      }
      val _ = proc.join(60_000)
      assert(
        events.exists(_.isInstanceOf[OrcaEvent.LlmOutput]),
        s"expected at least one LlmOutput event, got: $events"
      )
    finally if proc.isAlive() then proc.destroy(shutdownGracePeriod = 0)
