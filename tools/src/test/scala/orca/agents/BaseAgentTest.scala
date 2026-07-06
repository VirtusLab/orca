package orca.agents

import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  SessionRegistry,
  SessionSupport
}
import orca.events.{OrcaListener, Usage}

/** Pins `BaseAgent.autonomous.run`'s returned session id to the caller's client
  * handle rather than the backend's wire id. The two intentionally diverge for
  * backends whose wire id is server-minted (codex, gemini, opencode) —
  * `AgentResult.wireId` exists so the registry can learn that mapping, not for
  * callers to receive as their handle. Both ids are string-shaped, so a future
  * refactor that swapped in `result.wireId` would not fail to compile; this
  * test exists to catch that regression.
  */
class BaseAgentTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  test("autonomous.run returns the caller's client handle, not the wire id"):
    val session = SessionId[BackendTag.Pi.type]("client-session-id")
    val tool = new StubTool(StubBackend)
    val (returned, output) = tool.autonomous.run("prompt", session)
    assertEquals(
      returned,
      session,
      "returned id must be the caller's handle, not the wire id"
    )
    assertEquals(output, "out")

  test("close() delegates to the backend"):
    val backend = new RecordingCloseBackend
    val tool = new StubTool(backend)
    tool.close()
    assertEquals(backend.closeCount, 1)

  private class StubTool(backend: AgentBackend[BackendTag.Pi.type])
      extends BaseAgent[BackendTag.Pi.type, Agent[BackendTag.Pi.type]](
        backend,
        AgentConfig.default,
        StubPrompts,
        os.temp.dir(),
        OrcaListener.noop,
        StubInteraction
      ):
    val name: String = "stub"
    protected def copyTool(
        config: AgentConfig = AgentConfig.default,
        name: String = name
    ): Agent[BackendTag.Pi.type] = this

  private object StubBackend extends AgentBackend[BackendTag.Pi.type]:
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      AgentResult(
        WireSessionId[BackendTag.Pi.type]("server-wire-id"),
        "out",
        Usage.empty
      )
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)
    val tag: BackendTag.Pi.type = BackendTag.Pi

  private class RecordingCloseBackend extends AgentBackend[BackendTag.Pi.type]:
    var closeCount: Int = 0
    override def close(): Unit = closeCount += 1
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] = ???
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] = ???
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)
    val tag: BackendTag.Pi.type = BackendTag.Pi

  private object StubPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  private object StubInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B]): AgentResult[B] =
      ???
