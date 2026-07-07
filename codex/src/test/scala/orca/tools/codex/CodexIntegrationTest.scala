package orca.tools.codex

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  SessionId,
  WireSessionId
}
import orca.backend.{ConversationEvent, SupervisedBackend}
import orca.subprocess.OsProcCliRunner

/** End-to-end tests against the real `codex` CLI. Gated on the
  * `ORCA_INTEGRATION` environment variable so `sbt test` without the flag
  * behaves like a pure unit suite. Requires `codex` to be installed and
  * authenticated on the host.
  *
  * Sandbox-aware: the tests use [[AutoApprove.All]] (i.e.
  * `--dangerously-bypass-approvals-and-sandbox`) so they don't depend on
  * bubblewrap or unprivileged user namespaces, which aren't available in every
  * CI environment.
  */
class CodexIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    3.minutes

  private def withBackend(
      workDir: os.Path = os.temp.dir()
  )(body: ox.Ox ?=> CodexBackend => Unit): Unit =
    SupervisedBackend.using(
      new CodexBackend(OsProcCliRunner, workDir = workDir)
    )(body)

  private val unsandboxed: AgentConfig =
    AgentConfig().copy(autoApprove = AutoApprove.All)

  private def fresh = SessionId.fresh[BackendTag.Codex.type]

  test("headless prompt returns the requested literal output"):
    withBackend(): backend =>
      val result = backend.runAutonomous(
        prompt =
          "Reply with the single word: READY. Reply with that word and nothing else.",
        session = fresh,
        config = unsandboxed
      )
      assert(
        result.output.toUpperCase.contains("READY"),
        s"expected output to contain READY, got: ${result.output}"
      )
      assert(WireSessionId.value(result.wireId).nonEmpty)

  test("a resumed call carries conversational context across turns"):
    withBackend(): backend =>
      val session = fresh
      val _ = backend.runAutonomous(
        prompt = "Remember the number 42. Reply with the single word: stored.",
        session = session,
        config = unsandboxed
      )
      val second = backend.runAutonomous(
        prompt =
          "What number did I ask you to remember? Reply with just the number.",
        session = session,
        config = unsandboxed
      )
      assert(
        second.output.contains("42"),
        s"expected resumed session to recall '42', got: ${second.output}"
      )

  test("interactive session reaches a result with a session id"):
    withBackend(): backend =>
      val conversation = backend.runInteractive(
        prompt = "Reply with just the number 7. Nothing else.",
        session = fresh,
        displayPrompt = "reply with 7",
        config = unsandboxed,
        outputSchema = None
      )
      try
        conversation.events.foreach(_ => ())
        val Right(result) = conversation.awaitResult(): @unchecked
        assert(
          result.output.contains("7"),
          s"expected a reply containing '7', got: ${result.output}"
        )
        assert(WireSessionId.value(result.wireId).nonEmpty)
      finally conversation.cancel()

  test("interactive session emits AssistantTextDelta + AssistantTurnEnd"):
    withBackend(): backend =>
      val conversation = backend.runInteractive(
        prompt =
          "Reply with: 1, 2, 3. Just those three numbers separated by commas, nothing else.",
        session = fresh,
        displayPrompt = "list 1..3",
        config = unsandboxed,
        outputSchema = None
      )
      try
        val events = conversation.events.toList
        val _ = conversation.awaitResult()
        assert(
          events.exists(_.isInstanceOf[ConversationEvent.AssistantTextDelta]),
          s"expected an AssistantTextDelta; got: $events"
        )
        assert(
          events.contains(ConversationEvent.AssistantTurnEnd),
          s"expected an AssistantTurnEnd; got: $events"
        )
      finally conversation.cancel()

  test("a tool-using prompt surfaces a ToolResult"):
    val workDir = os.temp.dir()
    os.write(workDir / "marker.txt", "orca-codex-marker")
    withBackend(workDir): backend =>
      val conversation = backend.runInteractive(
        prompt =
          "You MUST run the shell command `cat marker.txt` first to read the file. Then tell me what it contained. Reply briefly.",
        session = fresh,
        displayPrompt = "read marker.txt",
        config = unsandboxed,
        outputSchema = None
      )
      try
        val events = conversation.events.toList
        val _ = conversation.awaitResult()
        // Models occasionally answer from context without invoking bash.
        // Assert the ToolResult side specifically — if codex did run the
        // shell, we'll see one.
        assert(
          events.exists(_.isInstanceOf[ConversationEvent.ToolResult]),
          s"expected a ToolResult after a directed tool-using prompt; got: $events"
        )
      finally conversation.cancel()
