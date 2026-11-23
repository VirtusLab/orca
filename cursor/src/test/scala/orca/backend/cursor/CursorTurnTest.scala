package orca.backend.cursor

import orca.agents.{Model, WireSessionId}
import orca.backend.TurnEvent
import orca.backend.TurnEventConformance
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class CursorTurnTest extends munit.FunSuite:

  private def liveTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  private def initLine(
      sessionId: String = "sess-1",
      model: String = "composer-2.5"
  ): String =
    s"""{"type":"system","subtype":"init","session_id":"$sessionId","model":"$model"}"""

  private def assistantLine(text: String): String =
    s"""{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

  private def resultLine(
      text: String = "OK",
      input: Long = 10L,
      output: Long = 3L
  ): String =
    s"""{"type":"result","subtype":"success","is_error":false,"result":"$text","usage":{"inputTokens":$input,"outputTokens":$output,"cacheReadTokens":0,"cacheWriteTokens":0}}"""

  liveTest("accumulates assistant text and settles on result"):
    val process = new FakePipedCliProcess()
    val live = CursorTurn(process)
    process.enqueueStdout(initLine())
    process.enqueueStdout(assistantLine("hello"))
    process.enqueueStdout(resultLine("hello", input = 10L, output = 3L))
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assertEquals(events, List(TurnEvent.AssistantTextDelta("hello"), TurnEvent.AssistantMessageEnd))
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(WireSessionId.value(r.wireId), "sess-1")
    assertEquals(r.output, "hello")
    assertEquals(r.model.map(_.name), Some("composer-2.5"))