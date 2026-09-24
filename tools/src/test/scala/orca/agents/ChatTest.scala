package orca.agents

import orca.testkit.{ScriptedBackend, TestAgent}
import orca.backend.{AgentBackend, AgentResult, TurnRequest}

/** The [[Chat]] handle contract: one conversation id threads through every
  * turn, `agent.run` mints a fresh one per call, and `agent.chat(continueFrom)`
  * adopts the given id, only while the backend holds it. The underlying engine
  * (retry, events, config precedence) is covered by `AgentTest` /
  * `AgentCallTest`.
  */
class ChatTest extends munit.FunSuite:

  private given orca.InStage = orca.InStage.unsafe

  test("every chat turn runs against the same conversation id"):
    val backend = new RecordingSessionBackend
    val chat = chatStubTool(backend).chat()
    val _ = chat.run("first")
    val _ = chat.run("second")
    assertEquals(backend.seen.distinct, List(SessionId.value(chat.id)))
    assertEquals(backend.seen.size, 2)

  test("agent.run mints a fresh conversation per call"):
    val backend = new RecordingSessionBackend
    val agent = chatStubTool(backend)
    val _ = agent.run("first")
    val _ = agent.run("second")
    assertEquals(backend.seen.distinct.size, 2)

  test("agent.chat(continueFrom) adopts the given conversation id"):
    val backend = new RecordingSessionBackend
    val agent = chatStubTool(backend)
    val opened = agent.chat()
    val _ = opened.run("open")
    val _ = agent.chat(opened.id).run("continue")
    val id = SessionId.value(opened.id)
    assertEquals(backend.seen, List(id, id))

  test("an adopted chat refuses structured turns the backend cannot continue"):
    val backend = new RecordingSessionBackend
    val adopted =
      chatStubTool(backend).chat(SessionId.fresh[BackendTag.Pi.type])
    val _ = intercept[ConversationNotHeld]:
      adopted.resultAs[Reply].autonomous.run("continue")
    val _ = intercept[ConversationNotHeld]:
      adopted.resultAs[Reply].interactive.run("continue")
    assertEquals(backend.seen, Nil)

  private case class Reply(text: String) derives JsonData

  /** Records the session id of every `runAutonomous` call. */
  private class RecordingSessionBackend extends ScriptedBackend(BackendTag.Pi):
    var seen: List[String] = Nil
    protected def reply(
        turn: TurnRequest[BackendTag.Pi.type]
    ): AgentResult[BackendTag.Pi.type] =
      seen = seen :+ SessionId.value(turn.session)
      ScriptedBackend.result("out")

  private def chatStubTool(
      backend: AgentBackend[BackendTag.Pi.type]
  ): Agent[BackendTag.Pi.type] =
    TestAgent(backend, "chat-stub")
