package orca.tools.claude

import orca.llm.{AutoApprove, LlmConfig, SessionId}
import orca.backend.{ApprovalDecision, ConversationEvent}
import orca.subprocess.OsProcCliRunner
import ox.channels.BufferCapacity
import ox.supervised

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

  /** Run a test body that needs a real ClaudeBackend in its own Ox scope. The
    * backend's interactive path spins up an MCP server fork; the scope boundary
    * releases it before the test returns.
    */
  private def withBackend(body: ClaudeBackend => Unit): Unit =
    supervised:
      given BufferCapacity = BufferCapacity(8)
      body(new ClaudeBackend(OsProcCliRunner))

  test("headless prompt returns the requested literal output"):
    withBackend: backend =>
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
    withBackend: backend =>
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

  test("stream-json interactive session reaches a Result with a session id"):
    withBackend: backend =>
      val conversation = backend.runInteractive(
        prompt = "Reply with just the number 7. Nothing else.",
        displayPrompt = "reply with 7",
        config = LlmConfig.default,
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
        displayPrompt = "count 1..5",
        config = LlmConfig.default,
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
        displayPrompt = "read /etc/hostname",
        config =
          LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set.empty)),
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
