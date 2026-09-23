package orca.backend

import orca.OrcaFlowException
import orca.agents.{BackendTag, WireSessionId}
import orca.events.{OrcaEvent, Usage}
import orca.testkit.ScriptedConversation

class ObservedConversationTest extends munit.FunSuite:

  private val sampleResult = AgentResult[BackendTag.Codex.type](
    wireId = WireSessionId[BackendTag.Codex.type]("sid"),
    output = "out",
    usage = Usage.empty
  )

  private val approveBash = ConversationEvent.ApproveTool(
    "Bash",
    """{"command":"ls"}""",
    _ => ()
  )

  test("the opening UserMessage surfaces as OrcaEvent.UserPrompt"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(ConversationEvent.UserMessage("fix the build")),
      Right(sampleResult)
    )
    val _ = ObservedConversation(conv, recorder).drain(_ => ())
    assertEquals(recorder.events, List(OrcaEvent.UserPrompt("fix the build")))

  test("a tool call reaches the listener before the next event is answered"):
    val recorder = new RecordingListener
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantToolCall("Read", """{"file":"a"}"""),
        approveBash
      ),
      Right(sampleResult)
    )
    var seenAtAnswer: List[OrcaEvent] = Nil
    val _ = ObservedConversation(conv, recorder).drain: _ =>
      seenAtAnswer = recorder.events
    assertEquals(
      seenAtAnswer,
      List(OrcaEvent.ToolUse("Read", """{"file":"a"}"""))
    )

  test("an answer that throws flushes the withheld turn, then rethrows"):
    // The closing turn could not be told from narration, so it shows.
    val recorder = new RecordingListener
    val crash = new OrcaFlowException("stdin closed")
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.AssistantTextDelta("narration"),
        ConversationEvent.AssistantTurnEnd,
        approveBash
      ),
      Right(sampleResult),
      outputSchema = Some("""{"type":"object"}""")
    )
    val thrown = intercept[OrcaFlowException]:
      ObservedConversation(conv, recorder).drain(_ => throw crash)
    assertEquals(thrown, crash)
    assertEquals(recorder.events, List(OrcaEvent.AssistantMessage("narration")))
