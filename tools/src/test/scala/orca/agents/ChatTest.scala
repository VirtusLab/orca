package orca.agents

import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  IdScheme,
  SessionSupport
}
import orca.events.{OrcaListener, Usage}

/** The [[Chat]] handle contract: one conversation id threads through every
  * turn, `agent.run` mints a fresh one per call, and `agent.chat(continueFrom)`
  * adopts the given id. The underlying engine (retry, events, config
  * precedence) is covered by `BaseAgentTest` / `DefaultAgentCallTest`.
  */
class ChatTest extends munit.FunSuite:

  private given orca.InStage = orca.InStage.unsafe

  test("every chat turn runs against the same conversation id"):
    val backend = new RecordingSessionBackend
    val chat = new ChatStubTool(backend).chat()
    val _ = chat.run("first")
    val _ = chat.run("second")
    assertEquals(backend.seen.distinct, List(SessionId.value(chat.id)))
    assertEquals(backend.seen.size, 2)

  test("agent.run mints a fresh conversation per call"):
    val backend = new RecordingSessionBackend
    val agent = new ChatStubTool(backend)
    val _ = agent.run("first")
    val _ = agent.run("second")
    assertEquals(backend.seen.distinct.size, 2)

  test("agent.chat(continueFrom) adopts the given conversation id"):
    val backend = new RecordingSessionBackend
    val adopted = SessionId.fresh[BackendTag.Pi.type]
    val _ = new ChatStubTool(backend).chat(adopted).run("continue")
    assertEquals(backend.seen, List(SessionId.value(adopted)))

  /** Records the session id of every `runAutonomous` call. */
  private class RecordingSessionBackend
      extends AgentBackend[BackendTag.Pi.type]:
    val workDir: os.Path = os.pwd
    var seen: List[String] = Nil
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] =
      seen = seen :+ SessionId.value(session)
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
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.ephemeral(IdScheme.ClientClaimed)
    val tag: BackendTag.Pi.type = BackendTag.Pi
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText

  private object ChatStubPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: AgentConfig,
        mode: StructuredOutputMode
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  private object ChatStubInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B])(using
        ox.Ox
    ): AgentResult[B] = ???

  private class ChatStubTool(
      backend: AgentBackend[BackendTag.Pi.type]
  ) extends BaseAgent[BackendTag.Pi.type, Agent[BackendTag.Pi.type]](
        backend,
        AgentConfig(),
        ChatStubPrompts,
        OrcaListener.noop,
        ChatStubInteraction
      ):
    val name: String = "chat-stub"
    protected def copyTool(
        config: AgentConfig = AgentConfig(),
        name: String = name,
        role: Option[String] = None
    ): Agent[BackendTag.Pi.type] = this
