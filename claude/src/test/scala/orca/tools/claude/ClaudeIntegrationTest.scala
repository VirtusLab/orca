package orca.tools.claude

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  SessionId,
  WireSessionId
}
import orca.backend.{ConversationEvent, SupervisedBackend}
import orca.subprocess.OsProcCliRunner
import orca.testkit.TempDirs

/** End-to-end tests against the real `claude` CLI. Gated on the
  * `ORCA_INTEGRATION` environment variable so `sbt test` without the flag
  * behaves like a pure unit suite. Require `claude` to be installed and
  * authenticated on the host.
  */
class ClaudeIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    2.minutes

  private def withBackend(body: ox.Ox ?=> ClaudeBackend => Unit): Unit =
    SupervisedBackend.using(
      new ClaudeBackend(OsProcCliRunner, workDir = TempDirs.dir())
    )(body)

  private def fresh = SessionId.fresh[BackendTag.ClaudeCode.type]

  test("headless prompt returns the requested literal output"):
    withBackend: backend =>
      val result = backend.runAutonomous(
        prompt = "Reply with the single word: READY",
        session = fresh,
        config = AgentConfig()
      )
      assert(
        result.output.contains("READY"),
        s"expected output to contain READY, got: ${result.output}"
      )
      assert(WireSessionId.value(result.wireId).nonEmpty)

  test("a resumed call carries conversational context across turns"):
    withBackend: backend =>
      val session = fresh
      val _ = backend.runAutonomous(
        prompt = "Remember the number 42. Reply with: stored.",
        session = session,
        config = AgentConfig()
      )
      val second = backend.runAutonomous(
        prompt = "What number did I ask you to remember?",
        session = session,
        config = AgentConfig()
      )
      assert(
        second.output.contains("42"),
        s"expected resumed session to recall '42', got: ${second.output}"
      )

  test("stream-json interactive session reaches a Result with a session id"):
    withBackend: backend =>
      val conversation = backend.runInteractive(
        prompt = "Reply with just the number 7. Nothing else.",
        session = fresh,
        displayPrompt = "reply with 7",
        config = AgentConfig(),
        outputSchema = None
      )
      try
        // Drain events so the driver can process them; we don't render
        // anything in the integration test — awaitResult gives the outcome.
        conversation.events.foreach(_ => ())
        val Right(result) = conversation.awaitResult(): @unchecked
        assert(
          result.output.contains("7"),
          s"expected a reply containing '7', got: ${result.output}"
        )
        assert(WireSessionId.value(result.wireId).nonEmpty)
      finally conversation.cancel()

  test("stream-json session emits text deltas as the agent streams"):
    withBackend: backend =>
      val conversation = backend.runInteractive(
        prompt =
          "Count from 1 to 5, one per line, then stop. Do not emit anything else.",
        session = fresh,
        displayPrompt = "count 1..5",
        config = AgentConfig(),
        outputSchema = None
      )
      try
        val events = conversation.events.toList
        val _ = conversation.awaitResult()
        assert(
          events.exists(_.isInstanceOf[ConversationEvent.AssistantTextDelta]),
          s"expected at least one AssistantTextDelta; got: $events"
        )
        assert(
          events.exists(_ == ConversationEvent.AssistantTurnEnd),
          s"expected an AssistantTurnEnd; got: $events"
        )
      finally conversation.cancel()

  test(
    "an unapproved tool is refused by the CLI, not negotiated over stdin"
  ):
    withBackend: backend =>
      // A future CLI that revives the `can_use_tool` subchannel fails here
      // first — see `ClaudeConversation.writeOutbound`.
      val conversation = backend.runInteractive(
        prompt = "Read the file at /etc/hostname and reply with its contents.",
        session = fresh,
        displayPrompt = "read /etc/hostname",
        config = AgentConfig().copy(autoApprove = AutoApprove.Only(Set.empty)),
        outputSchema = None
      )
      try
        // Wide window: with `--include-partial-messages` a chatty preamble
        // easily pushes the tool_result past the first handful of events.
        val firstFew = conversation.events.take(40).toList
        assert(
          !firstFew.exists(_.isInstanceOf[ConversationEvent.ApproveTool]),
          s"claude routed a tool approval over stdio; stdin is closed at spawn, so the response would throw: $firstFew"
        )
        assert(
          firstFew.exists:
            case ConversationEvent.ToolResult(_, ok, _) => !ok
            case _                                      => false
          ,
          s"expected the refused Read to surface as a failed tool_result: $firstFew"
        )
      finally conversation.cancel()
