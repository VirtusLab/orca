package orca.agents

import orca.testkit.ScriptedBackend
import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  TurnRequest
}
import orca.events.OrcaListener

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
  private class RecordingSessionBackend extends ScriptedBackend(BackendTag.Pi):
    var seen: List[String] = Nil
    protected def reply(
        turn: TurnRequest[BackendTag.Pi.type]
    ): AgentResult[BackendTag.Pi.type] =
      seen = seen :+ SessionId.value(turn.session)
      ScriptedBackend.result("out")

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
    def retry(
        failedResponse: String,
        parseError: String,
        mode: StructuredOutputMode
    ): String = ???

  private object ChatStubInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B]): AgentResult[B] =
      ???

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
