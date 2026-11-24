package orca.backend.cursor

import orca.AgentTurnFailed
import orca.agents.WireSessionId
import orca.events.TurnDebit
import orca.testkit.TempDirs
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
    assert(!live.canAskUser)
    process.enqueueStdout(initLine())
    process.enqueueStdout(assistantLine("hello"))
    process.enqueueStdout(resultLine("hello", input = 10L, output = 3L))
    process.closeStdout()
    process.closeStderr()

    val events = live.events.toList
    assertEquals(
      events,
      List(TurnEvent.AssistantTextDelta("hello"), TurnEvent.AssistantMessageEnd)
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val Right(r) = live.awaitResult(): @unchecked
    assertEquals(WireSessionId.value(r.wireId), "sess-1")
    assertEquals(r.output, "hello")
    assertEquals(r.model.map(_.name), Some("composer-2.5"))
  private def finish(process: FakePipedCliProcess): Unit =
    process.closeStdout()
    process.closeStderr()

  liveTest(
    "interleaved messages and tools retain call ids and nested payloads"
  ):
    val process = new FakePipedCliProcess()
    val started =
      """{"type":"tool_call","subtype":"started","call_id":"read-1","tool_call":{"readToolCall":{"args":{"path":"README.md"}}}}"""
    val completed =
      """{"type":"tool_call","subtype":"completed","call_id":"read-1","tool_call":{"readToolCall":{"args":{"path":"README.md"},"result":{"success":{"content":"hello"}}}}}"""
    val live = CursorTurn(process)
    List(
      initLine(),
      assistantLine("looking"),
      started,
      """{"type":"future"}""",
      completed,
      assistantLine("done"),
      resultLine()
    ).foreach(process.enqueueStdout)
    finish(process)
    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.AssistantTextDelta("looking"),
        TurnEvent.AssistantMessageEnd,
        TurnEvent
          .AssistantToolCall(toolName = "readToolCall", rawInput = started),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.ToolResult(Some("readToolCall"), true, completed),
        TurnEvent.AssistantMessageEnd,
        TurnEvent.AssistantTextDelta("done"),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    assertEquals(live.awaitResult().toOption.get.output, "lookingdone")

  liveTest("completed failed tool retains payload and failure status"):
    val process = new FakePipedCliProcess()
    val completed =
      """{"type":"tool_call","subtype":"completed","call_id":"read-2","tool_call":{"readToolCall":{"result":{"error":{"message":"missing"}}}}}"""
    val live = CursorTurn(process)
    List(initLine(), completed, resultLine()).foreach(process.enqueueStdout)
    finish(process)
    val events = live.events.toList
    assertEquals(
      events,
      List(
        TurnEvent.ToolResult(Some("readToolCall"), false, completed),
        TurnEvent.AssistantMessageEnd
      )
    )
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    assert(live.awaitResult().isRight)

  liveTest(
    "malformed and unknown lines preserve subsequent message boundaries"
  ):
    val process = new FakePipedCliProcess()
    val live = CursorTurn(process)
    List(
      initLine(),
      "not-json",
      """{"type":"tool_call","subtype":"future","call_id":"x"}""",
      assistantLine(""),
      assistantLine("answer"),
      resultLine()
    ).foreach(process.enqueueStdout)
    finish(process)
    val events = live.events.toList
    assertEquals(events.count(_.isInstanceOf[TurnEvent.Error]), 1)
    assertEquals(events.count(_ == TurnEvent.AssistantMessageEnd), 1)
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    assert(live.awaitResult().isRight)

  liveTest(
    "terminal failure without usage remains unobserved and closes messages"
  ):
    val process = new FakePipedCliProcess()
    val live = CursorTurn(process)
    List(
      initLine(),
      assistantLine("trying"),
      """{"type":"result","subtype":"error","is_error":true,"result":"provider failed"}"""
    ).foreach(process.enqueueStdout)
    finish(process)
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val error = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(error.debit, TurnDebit.Unobserved)
    assert(error.getMessage.contains("provider failed"))

  liveTest("terminal failure with usage preserves fresh and cached inputs"):
    val process = new FakePipedCliProcess()
    val live = CursorTurn(process)
    List(
      initLine(),
      """{"type":"result","subtype":"error","usage":{"inputTokens":709,"cacheReadTokens":16448,"cacheWriteTokens":0,"outputTokens":53}}"""
    ).foreach(process.enqueueStdout)
    finish(process)
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val error = intercept[AgentTurnFailed](live.awaitResult())
    val TurnDebit.Observed(usage, _) = error.debit: @unchecked
    assertEquals(usage.inputTokens, 17157L)
    assertEquals(usage.outputTokens, 53L)
    assertEquals(usage.cost, None)

  liveTest("process failure includes stderr and preserves completed messages"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    val live = CursorTurn(process)
    process.enqueueStdout(assistantLine("partial"))
    process.enqueueStderr("provider connection failed")
    finish(process)
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = false)
    val error = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(error.debit, TurnDebit.Unobserved)
    assert(error.getMessage.contains("exited with code 7"))
    assert(error.getMessage.contains("provider connection failed"))

  liveTest("stdout read error fails without inventing usage"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def stdoutLines: Iterator[String] =
        Iterator(initLine(), assistantLine("partial")) ++ Iterator.continually(
          throw new java.io.IOException("read failed")
        )
    process.closeStderr()
    val live = CursorTurn(process)
    TurnEventConformance.assertGrammar(
      live.events.toList,
      completedNormally = false
    )
    val error = intercept[AgentTurnFailed](live.awaitResult())
    assertEquals(error.debit, TurnDebit.Unobserved)
    assert(error.getMessage.contains("read failed"))

  for refusal <- List(
      "Workspace Trust Required",
      """{"type":"error","message":"Workspace Trust Required"}""",
      """{"type":"result","subtype":"error","result":"Workspace Trust Required"}"""
    )
  do
    liveTest(
      s"stdout trust refusal gives exact checkout instructions: $refusal"
    ):
      val process = new FakePipedCliProcess()
      val workDir = TempDirs.dir()
      val live = CursorTurn(process, workDir = workDir)
      process.enqueueStdout(refusal)
      finish(process)
      TurnEventConformance.assertGrammar(
        live.events.toList,
        completedNormally = true
      )
      val error = intercept[AgentTurnFailed](live.awaitResult())
      assert(error.getMessage.contains(workDir.toString))
      assert(error.getMessage.contains("cursor-agent`"))
      assert(
        error.getMessage.contains("interactively trust this exact checkout")
      )
      assert(error.getMessage.contains("rerun Orca"))

  liveTest("stderr trust refusal survives nonzero process exit with guidance"):
    val process = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(1)
    val workDir = TempDirs.dir()
    val live = CursorTurn(process, workDir = workDir)
    process.enqueueStderr("Workspace Trust Required")
    finish(process)
    val events = live.events.toList
    TurnEventConformance.assertGrammar(events, completedNormally = true)
    val error = intercept[AgentTurnFailed](live.awaitResult())
    assert(error.getMessage.contains(workDir.toString))
    assert(error.getMessage.contains("rerun Orca"))

  liveTest("assistant discussion of workspace trust is ordinary output"):
    val process = new FakePipedCliProcess()
    val live = CursorTurn(process)
    List(
      initLine(),
      assistantLine("Workspace Trust Required means trust the checkout"),
      resultLine()
    ).foreach(process.enqueueStdout)
    finish(process)
    TurnEventConformance.assertGrammar(
      live.events.toList,
      completedNormally = true
    )
    assert(live.awaitResult().isRight)
