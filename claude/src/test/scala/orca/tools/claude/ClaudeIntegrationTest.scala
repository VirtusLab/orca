package orca.tools.claude

import orca.agents.{AutoApprove, BackendTag, AgentConfig, SessionId}
import orca.backend.{ApprovalDecision, ConversationEvent, SupervisedBackend}
import orca.subprocess.OsProcCliRunner

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
    SupervisedBackend.using(new ClaudeBackend(OsProcCliRunner))(body)

  private def fresh = SessionId.fresh[BackendTag.ClaudeCode.type]

  test("headless prompt returns the requested literal output"):
    withBackend: backend =>
      val result = backend.runAutonomous(
        prompt = "Reply with the single word: READY",
        session = fresh,
        config = AgentConfig.default,
        workDir = os.temp.dir()
      )
      assert(
        result.output.contains("READY"),
        s"expected output to contain READY, got: ${result.output}"
      )
      assert(SessionId.value(result.sessionId).nonEmpty)

  test("a resumed call carries conversational context across turns"):
    withBackend: backend =>
      val workDir = os.temp.dir()
      val session = fresh
      val _ = backend.runAutonomous(
        prompt = "Remember the number 42. Reply with: stored.",
        session = session,
        config = AgentConfig.default,
        workDir = workDir
      )
      val second = backend.runAutonomous(
        prompt = "What number did I ask you to remember?",
        session = session,
        config = AgentConfig.default,
        workDir = workDir
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
        config = AgentConfig.default,
        workDir = os.temp.dir(),
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
        assert(SessionId.value(result.sessionId).nonEmpty)
      finally conversation.cancel()

  test("stream-json session emits text deltas as the agent streams"):
    withBackend: backend =>
      val conversation = backend.runInteractive(
        prompt =
          "Count from 1 to 5, one per line, then stop. Do not emit anything else.",
        session = fresh,
        displayPrompt = "count 1..5",
        config = AgentConfig.default,
        workDir = os.temp.dir(),
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
    "ApproveTool fires for a tool call when autoApprove is empty; denying shuts the session cleanly"
  ):
    withBackend: backend =>
      // This test assumes claude CLI routes tool-approval through the
      // control_request subchannel when the caller's permission mode
      // doesn't pre-authorise. If the installed CLI doesn't expose this
      // path, the driver simply won't see an ApproveTool event — the
      // test will then fail with a clearer signal than a silent gap.
      val conversation = backend.runInteractive(
        prompt = "Read the file at /etc/hostname and reply with its contents.",
        session = fresh,
        displayPrompt = "read /etc/hostname",
        config =
          AgentConfig.default.copy(autoApprove = AutoApprove.Only(Set.empty)),
        workDir = os.temp.dir(),
        outputSchema = None
      )
      try
        val firstFew = conversation.events.take(10).toList
        val approval = firstFew.collectFirst {
          case evt: ConversationEvent.ApproveTool => evt
        }
        approval match
          case Some(ConversationEvent.ApproveTool(name, _, respond)) =>
            assert(name.nonEmpty)
            respond(ApprovalDecision.Deny(Some("test denies all tools")))
            // Drain remaining events and confirm the session finishes.
            conversation.events.foreach(_ => ())
            val _ = conversation.awaitResult()
          case None =>
            fail(
              s"no ApproveTool event in first 10 events — CLI may not route tool approvals through stdio: $firstFew"
            )
      finally conversation.cancel()
