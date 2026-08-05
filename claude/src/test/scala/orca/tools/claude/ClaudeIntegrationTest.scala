package orca.tools.claude

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  SessionId,
  ToolSet,
  WireSessionId
}
import orca.backend.{ConversationEvent, Dispatch, SupervisedBackend}
import orca.subprocess.OsProcCliRunner
import orca.testkit.TempDirs
import orca.tools.claude.streamjson.OutboundMessage

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
    "a read the CLI refuses is reported as a failed tool_result, never prompted over stdin"
  ):
    withBackend: backend =>
      // The CLI refuses `/etc/hostname` because it is outside the backend's
      // workDir; a read inside the workspace runs fine, since `Only(empty)`
      // emits no flags at all and default permission mode allows workspace
      // reads. What is pinned is not the refusal but its shape: it arrives as a
      // failed tool_result, not as a `can_use_tool` control request. Stdin is
      // closed at spawn, so orca could not answer such a request — a future CLI
      // reviving that subchannel fails here first (see
      // `ClaudeConversation.respond`).
      val conversation = backend.runInteractive(
        prompt = "Read the file at /etc/hostname and reply with its contents.",
        session = fresh,
        displayPrompt = "read /etc/hostname",
        config = AgentConfig().copy(autoApprove = AutoApprove.Only(Set.empty)),
        outputSchema = None
      )
      try
        val events = conversation.events.toList
        assert(
          !events.exists(_.isInstanceOf[ConversationEvent.ApproveTool]),
          s"claude routed a tool approval over stdio, which orca cannot answer: $events"
        )
        assert(
          events.exists:
            case ConversationEvent.ToolResult(_, ok, _) => !ok
            case _                                      => false
          ,
          s"expected the refused Read to surface as a failed tool_result: $events"
        )
      finally conversation.cancel()

  // --- `--tools` allowlist ---
  //
  // The CLI drops an unknown tool name silently: `--tools Read,Grep,NoSuchTool`
  // yields an init list of `Grep,Read`, exit 0, no warning. A rename upstream
  // would strip a tool from every read-only turn with no signal, so the two
  // shipped allowlists are pinned against the live CLI here.

  test("the ReadOnly allowlist reaches claude with every name intact"):
    assertEquals(
      grantedTools(AgentConfig(tools = ToolSet.ReadOnly), Seq.empty),
      ClaudeArgs.ReadOnlyTools.toSet
    )

  test("the NetworkOnly allowlist reaches claude with every name intact"):
    assertEquals(
      grantedTools(
        AgentConfig(tools = ToolSet.NetworkOnly),
        ClaudeBackend.DefaultNetworkTools
      ),
      (ClaudeArgs.ReadOnlyTools ++ ClaudeBackend.DefaultNetworkTools).toSet
    )

  /** Run the shipped args for `config` and return the built-in tools claude
    * announces in its `system.init` frame. `mcp__*` names are excluded: they
    * pass through `--tools` unfiltered and depend on the host's MCP config.
    */
  private def grantedTools(
      config: AgentConfig,
      networkTools: Seq[String]
  ): Set[String] =
    val args = ClaudeArgs.streamJson(
      config = config,
      systemPromptFile = None,
      dispatch = Dispatch.Fresh(
        Some(
          WireSessionId[BackendTag.ClaudeCode.type](
            java.util.UUID.randomUUID().toString
          )
        )
      ),
      networkTools = networkTools
    )
    val stdout = os
      .proc(args)
      .call(
        cwd = TempDirs.dir(),
        stdin = OutboundMessage.toJson(OutboundMessage.UserText("Reply: ok")) +
          "\n"
      )
      .out
      .lines()
    val init = stdout
      .find(_.contains("\"subtype\":\"init\""))
      .getOrElse(fail(s"no init frame in claude's output: $stdout"))
    readFromString[InitTools](init).tools
      .filterNot(_.startsWith("mcp__"))
      .toSet

private case class InitTools(tools: List[String])
    derives ConfiguredJsonValueCodec
