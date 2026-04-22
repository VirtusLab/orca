package orca.claude

import orca.{Backend, LlmConfig, OrcaFlowException, SessionId}
import orca.subprocess.{CliProcess, StubCliRunner}

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.DurationInt

/** A CliProcess that a test can poke into states via helper methods. */
class FakeCliProcess(initiallyAlive: Boolean = true) extends CliProcess:
  private val alive = AtomicBoolean(initiallyAlive)
  private val sigintCount = AtomicInteger(0)

  def sendSigInt(): Unit =
    val _ = sigintCount.incrementAndGet()
    alive.set(false)
  def isAlive: Boolean = alive.get()
  def waitForExit(): Int = 0

  def markDead(): Unit = alive.set(false)
  def sigIntCount: Int = sigintCount.get()

class ClaudeInteractiveTest extends munit.FunSuite:

  private def withSentinel[A](sessionIdValue: String)(body: os.Path => A): A =
    val sentinel = ClaudeStopHook.sentinelPath(sessionIdValue)
    try body(sentinel)
    finally
      if os.exists(sentinel) then
        val _ = os.remove(sentinel)

  test("launchInteractive spawns claude interactively with --session-id"):
    val cli = new StubCliRunner(processFactory = () => new FakeCliProcess())
    val backend = new ClaudeBackend(cli)
    val _ = backend.launchInteractive(
      "plan the work",
      LlmConfig.default,
      os.temp.dir()
    )

    val args = cli.lastSpawn.getOrElse(fail("expected a spawn")).args
    assert(!args.contains("-p"), "interactive mode must not use -p")
    assert(args.containsSlice(Seq("claude", "plan the work")))
    val sessionIdIdx = args.indexOf("--session-id")
    assert(sessionIdIdx >= 0, "--session-id flag is required")
    // Backend generates UUIDs — verify the value parses as one.
    val _ = UUID.fromString(args(sessionIdIdx + 1))

  test(
    "awaitTermination returns the sentinel payload and SIGINTs a live process"
  ):
    val sessionIdStr = UUID.randomUUID().toString
    val sessionId = SessionId[Backend.ClaudeCode.type](sessionIdStr)
    withSentinel(sessionIdStr): sentinel =>
      os.write.over(sentinel, """{"ok":true}""")

      val process = new FakeCliProcess()
      val handle = new ClaudeInteractiveHandle(process, sessionId, 10.millis)

      val result = handle.awaitTermination()
      assertEquals(SessionId.value(result.sessionId), sessionIdStr)
      assertEquals(result.output, """{"ok":true}""")
      assertEquals(process.sigIntCount, 1)

  test("awaitTermination does not SIGINT a process that has already exited"):
    val sessionIdStr = UUID.randomUUID().toString
    val sessionId = SessionId[Backend.ClaudeCode.type](sessionIdStr)
    withSentinel(sessionIdStr): sentinel =>
      os.write.over(sentinel, "payload")

      val process = new FakeCliProcess(initiallyAlive = false)
      val handle = new ClaudeInteractiveHandle(process, sessionId, 10.millis)

      val result = handle.awaitTermination()
      assertEquals(result.output, "payload")
      assertEquals(process.sigIntCount, 0)

  test(
    "awaitTermination throws when the process dies without writing the sentinel"
  ):
    val sessionIdStr = UUID.randomUUID().toString
    val sessionId = SessionId[Backend.ClaudeCode.type](sessionIdStr)
    withSentinel(sessionIdStr): _ =>
      val process = new FakeCliProcess(initiallyAlive = false)
      val handle = new ClaudeInteractiveHandle(process, sessionId, 10.millis)

      intercept[OrcaFlowException]:
        handle.awaitTermination()
