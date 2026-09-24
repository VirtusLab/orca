package orca.tools.codex

import orca.testkit.OpenTurn
import orca.AgentTurnFailed
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Model,
  SessionId,
  ToolSet,
  WireSessionId
}
import orca.backend.{TurnEvent, SupervisedBackend}
import orca.subprocess.OsProcCliRunner
import orca.testkit.TempDirs

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
      workDir: os.Path = TempDirs.dir()
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
      val live = OpenTurn.interactive(backend)(
        prompt = "Reply with just the number 7. Nothing else.",
        session = fresh,
        displayPrompt = "reply with 7",
        config = unsandboxed,
        outputSchema = None
      )
      try
        live.events.foreach(_ => ())
        val Right(result) = live.awaitResult(): @unchecked
        assert(
          result.output.contains("7"),
          s"expected a reply containing '7', got: ${result.output}"
        )
        assert(WireSessionId.value(result.wireId).nonEmpty)
      finally live.cancel()

  test("interactive session emits AssistantTextDelta + AssistantMessageEnd"):
    withBackend(): backend =>
      val live = OpenTurn.interactive(backend)(
        prompt =
          "Reply with: 1, 2, 3. Just those three numbers separated by commas, nothing else.",
        session = fresh,
        displayPrompt = "list 1..3",
        config = unsandboxed,
        outputSchema = None
      )
      try
        val events = live.events.toList
        val _ = live.awaitResult()
        assert(
          events.exists(_.isInstanceOf[TurnEvent.AssistantTextDelta]),
          s"expected an AssistantTextDelta; got: $events"
        )
        assert(
          events.contains(TurnEvent.AssistantMessageEnd),
          s"expected an AssistantMessageEnd; got: $events"
        )
      finally live.cancel()

  test(
    "a reviewer-shaped turn (read-only, systemPrompt, pinned model) succeeds with a valid model"
  ):
    // Same shape `buildReviewers` drives: ReadOnly tools + a systemPrompt,
    // model pinned via AgentConfig rather than left to codex's default.
    withBackend(): backend =>
      val result = backend.runAutonomous(
        prompt = "Reply with the single word: READY.",
        session = fresh,
        config = AgentConfig(
          model = Some(Model("gpt-6-sol")),
          systemPrompt = Some("You are a terse reviewer."),
          tools = ToolSet.ReadOnly
        )
      )
      assert(
        result.output.toUpperCase.contains("READY"),
        s"expected output to contain READY, got: ${result.output}"
      )

  test(
    "an invalid/unsupported model pin surfaces codex's own explanation, not a bare exit code"
  ):
    // Regression test for the fork-flow failure: a reviewer configured with a
    // model name this codex build doesn't support used to fail with the
    // bare, undiagnosable "codex exited with code 1". It must now name the
    // actual problem, taken from codex's own `turn.failed` event.
    withBackend(): backend =>
      val ex = intercept[AgentTurnFailed]:
        val _ = backend.runAutonomous(
          prompt = "Reply with the single word: READY.",
          session = fresh,
          config = AgentConfig(
            model = Some(Model("gpt-0-orca-invalid")),
            systemPrompt = Some("You are a terse reviewer."),
            tools = ToolSet.ReadOnly
          )
        )
      assert(
        !ex.getMessage.trim.endsWith("exited with code 1"),
        s"expected codex's own explanation folded in, not a bare exit code; got: ${ex.getMessage}"
      )

  test(
    "structured call with a tool call before the answer produces exactly " +
      "one turn (regression for the `●` JSON leak)"
  ):
    // Reproduces the reported bug live: codex, when told to run a tool before
    // answering under `--output-schema`, sometimes emits an early "commentary"
    // agent_message (often identical to the eventual answer) before the tool
    // call, then the genuine final one. Before the fix, CodexTurn
    // closed a turn per agent_message, so the commentary message surfaced as
    // its own finished turn and got echoed as `AssistantMessage` prose by
    // `AutonomousDrain`' withholding buffer once the final turn closed.
    val workDir = TempDirs.dir()
    os.write(workDir / "marker.txt", "orca-codex-marker")
    withBackend(workDir): backend =>
      val live = OpenTurn.interactive(backend)(
        prompt =
          "You MUST run the shell command `cat marker.txt` first, then " +
            "respond with JSON only (no commentary): {\"issues\":[]}",
        session = fresh,
        displayPrompt = "structured tool-then-answer",
        config = unsandboxed.copy(model = Some(Model("gpt-6-luna"))),
        outputSchema = Some(
          """{"type":"object","properties":{"issues":{"type":"array","items":{"type":"string"}}},"required":["issues"],"additionalProperties":false}"""
        )
      )
      try
        val events = live.events.toList
        val _ = live.awaitResult()
        assertEquals(
          events.count(_ == TurnEvent.AssistantMessageEnd),
          1,
          s"expected every agent_message in this structured call to share " +
            s"one turn; got: $events"
        )
      finally live.cancel()

  test("a tool-using prompt surfaces a ToolResult"):
    val workDir = TempDirs.dir()
    os.write(workDir / "marker.txt", "orca-codex-marker")
    withBackend(workDir): backend =>
      val live = OpenTurn.interactive(backend)(
        prompt =
          "You MUST run the shell command `cat marker.txt` first to read the file. Then tell me what it contained. Reply briefly.",
        session = fresh,
        displayPrompt = "read marker.txt",
        config = unsandboxed,
        outputSchema = None
      )
      try
        val events = live.events.toList
        val _ = live.awaitResult()
        // Models occasionally answer from context without invoking bash.
        // Assert the ToolResult side specifically — if codex did run the
        // shell, we'll see one.
        assert(
          events.exists(_.isInstanceOf[TurnEvent.ToolResult]),
          s"expected a ToolResult after a directed tool-using prompt; got: $events"
        )
      finally live.cancel()
