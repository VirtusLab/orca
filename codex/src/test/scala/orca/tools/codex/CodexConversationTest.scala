package orca.tools.codex

import orca.agents.{Model, WireSessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.backend.{ConversationEvent, ConversationEventConformance}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class CodexConversationTest extends munit.FunSuite:

  /** Runs each test body in a fresh supervised scope, which `CodexConversation`
    * needs to fork its reader/stderr/ask-user workers. Tests managing their own
    * scope (the ask-user ones) stay on plain `test`.
    */
  private def convTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  convTest("agent_message item completes a turn with TextDelta + TurnEnd"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout(
      """{"type":"thread.started","thread_id":"thr-1"}"""
    )
    process.enqueueStdout("""{"type":"turn.started"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"hello"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":3,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assertEquals(
      events,
      List(
        ConversationEvent.AssistantTextDelta("hello"),
        ConversationEvent.AssistantTurnEnd
      )
    )
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(WireSessionId.value(result.wireId), "thr-1")
    assertEquals(result.output, "hello")
    assertEquals(result.usage, Usage(10L, 3L, None))

  convTest(
    "usage attributes to the configured model when the wire omits it"
  ):
    // `thread.started` here carries no `model` field (as codex's `resume` exec
    // often doesn't); without the configured-model fallback the turn's tokens
    // would land under `(unknown)` and go unpriced.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(
      process,
      configuredModel = Some(Model("gpt-5.4-mini"))
    )

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-cm"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":3,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.model, Some(Model("gpt-5.4-mini")))

  convTest("the wire's model wins over the configured fallback"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(
      process,
      configuredModel = Some(Model("configured-fallback"))
    )

    process.enqueueStdout(
      """{"type":"thread.started","thread_id":"thr-wm","model":"gpt-5.4"}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.model, Some(Model("gpt-5.4")))

  convTest("initialPrompt becomes a UserMessage event before agent output"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process, initialPrompt = "do the thing")

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-2"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"done"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assertEquals(
      events.head,
      ConversationEvent.UserMessage("do the thing")
    )
    val _ = conv.awaitResult()

  convTest("the LAST agent_message wins when a turn produces several"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-3"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"thinking..."}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"final answer"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":5,"output_tokens":2,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    // Each agent_message item closes its own turn, so two items means two turns
    // are KEPT; "last wins" only picks the final result text, below.
    assertEquals(events.count(_ == ConversationEvent.AssistantTurnEnd), 2)
    val Right(result) = conv.awaitResult(): @unchecked
    assertEquals(result.output, "final answer")

  convTest(
    "command_execution items become AssistantToolCall + ToolResult events"
  ):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-4"}""")
    process.enqueueStdout(
      """{"type":"item.started","item":{"id":"item_0","type":"command_execution","command":"/bin/bash -lc ls","aggregated_output":"","exit_code":null,"status":"in_progress"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"command_execution","command":"/bin/bash -lc ls","aggregated_output":"hello.txt\n","exit_code":0,"status":"completed"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    // A tool-only turn: AssistantToolCall + ToolResult open the turn, and
    // `turn.completed` → `succeedWith` auto-closes it with the owed
    // AssistantTurnEnd.
    assertEquals(events.size, 3)
    events(0) match
      case ConversationEvent.AssistantToolCall(name, rawInput) =>
        assertEquals(name, "bash")
        assert(rawInput.contains("/bin/bash -lc ls"))
      case other => fail(s"expected AssistantToolCall, got $other")
    events(1) match
      case ConversationEvent.ToolResult(name, ok, content) =>
        assertEquals(name, Some("bash"))
        assertEquals(ok, true)
        assertEquals(content, "hello.txt\n")
      case other => fail(s"expected ToolResult, got $other")
    assertEquals(events(2), ConversationEvent.AssistantTurnEnd)
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val _ = conv.awaitResult()

  convTest("command_execution with non-zero exit yields ok=false"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout(
      """{"type":"thread.started","thread_id":"thr-fail"}"""
    )
    process.enqueueStdout(
      """{"type":"item.started","item":{"id":"item_0","type":"command_execution","command":"false","exit_code":null,"status":"in_progress"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"command_execution","command":"false","aggregated_output":"","exit_code":1,"status":"failed"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    val toolResult = events
      .collectFirst { case r: ConversationEvent.ToolResult =>
        r
      }
      .getOrElse(fail("expected a ToolResult"))
    assertEquals(toolResult.ok, false)
    val _ = conv.awaitResult()

  convTest("file_change items become file_change tool calls and results"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-5"}""")
    process.enqueueStdout(
      """{"type":"item.started","item":{"id":"item_4","type":"file_change","changes":[{"path":"/x/y.txt","kind":"update"}],"status":"in_progress"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_4","type":"file_change","changes":[{"path":"/x/y.txt","kind":"update"}],"status":"completed"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    val toolCall = events
      .collectFirst {
        case c: ConversationEvent.AssistantToolCall
            if c.toolName == "file_change" =>
          c
      }
      .getOrElse(fail("expected file_change AssistantToolCall"))
    assert(toolCall.rawInput.contains("/x/y.txt"))
    assert(toolCall.rawInput.contains("update"))
    val toolResult = events
      .collectFirst {
        case r: ConversationEvent.ToolResult
            if r.toolName == Some("file_change") =>
          r
      }
      .getOrElse(fail("expected file_change ToolResult"))
    assertEquals(toolResult.ok, true)
    val _ = conv.awaitResult()

  convTest("reasoning items emit AssistantThinkingDelta when non-empty"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-r"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"reasoning","text":"checking inputs"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"done"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":4}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assertEquals(
      events.head,
      ConversationEvent.AssistantThinkingDelta("checking inputs")
    )
    val _ = conv.awaitResult()

  convTest(
    "cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"
  ):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)
    conv.cancel()
    conv.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected Left(OrcaInteractiveCancelled), got: $other")
    assertEquals(process.sigIntCount, 1)

  convTest(
    "clean process exit without turn.completed surfaces as OrcaFlowException"
  ):
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-x"}""")
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    // Abnormal termination: the stream ends before turn.completed, so a
    // trailing open turn is legal — completedNormally = false.
    ConversationEventConformance.assertGrammar(
      events,
      completedNormally = false
    )
    val ex = intercept[OrcaFlowException](conv.awaitResult())
    assert(
      ex.getMessage.contains("turn.completed"),
      s"expected the missing-turn.completed message; got: ${ex.getMessage}"
    )

  convTest(
    "malformed JSONL line surfaces as ConversationEvent.Error and the loop continues"
  ):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-bad"}""")
    process.enqueueStdout("not json at all")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(msg) => msg.contains("Failed to parse")
        case _                            => false
      },
      s"expected a parse-error event; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("stderr noise about reading stdin is filtered out"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStderr("Reading additional input from stdin...")
    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-q"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.exists {
        case ConversationEvent.Error(_) => true
        case _                          => false
      },
      s"benign stderr noise must not surface as Error events: $events"
    )
    val _ = conv.awaitResult()

  convTest(
    "`failed to record rollout items` shutdown noise is filtered out"
  ):
    // Codex emits this ERROR line during shutdown after the rollout writer is
    // torn down; the rollout file is still written correctly, so it's harmless
    // noise that would otherwise spam the user log on every call.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStderr(
      "2026-05-27T06:30:35.948974Z ERROR codex_core::session: " +
        "failed to record rollout items: thread 019e6820-ac92-7b01-9f3b-f12fbb65492b not found"
    )
    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-r"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.exists {
        case ConversationEvent.Error(_) => true
        case _                          => false
      },
      s"benign shutdown stderr must not surface as Error events: $events"
    )
    val _ = conv.awaitResult()

  convTest("real stderr lines surface as ConversationEvent.Error"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStderr("Error: thread/resume failed: not found")
    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-e"}""")
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(msg) =>
          msg.contains("thread/resume failed")
        case _ => false
      },
      s"expected a stderr-derived Error event; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("consecutive identical stderr lines collapse to a single Error"):
    // StderrPipeline suppresses a line identical to the one just surfaced (some
    // CLIs repeat the same warning on every invocation).
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStderr("Warning: some repeated warning")
    process.enqueueStderr("Warning: some repeated warning")
    process.enqueueStderr("Warning: some repeated warning")
    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-dup"}""")
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val errors = conv.events.toList.collect {
      case ConversationEvent.Error(msg) if msg.contains("repeated warning") =>
        msg
    }
    assertEquals(
      errors.size,
      1,
      s"3 identical stderr lines should surface once; got: $errors"
    )
    val _ = conv.awaitResult()

  convTest("interleaved different stderr lines all surface"):
    // Dedup only collapses CONSECUTIVE identical lines; an a/b/a run, where the
    // second `a` isn't adjacent to the first, surfaces all three.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStderr("Error: alpha")
    process.enqueueStderr("Error: beta")
    process.enqueueStderr("Error: alpha")
    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-mix"}""")
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val errors = conv.events.toList.collect {
      case ConversationEvent.Error(msg)
          if msg.contains("alpha") || msg.contains("beta") =>
        msg
    }
    assertEquals(
      errors.size,
      3,
      s"three distinct stderr lines should all surface; got: $errors"
    )
    val _ = conv.awaitResult()

  convTest("stderr strips terminal controls before surfacing as an Error"):
    // StderrPipeline strips ANSI/terminal control sequences before surfacing
    // stderr as an Error event.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStderr("auth[?25l failed[2K now")
    process.enqueueStdout(
      """{"type":"thread.started","thread_id":"thr-ansi"}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(msg) =>
          msg.contains("auth failed now") && !msg.contains("?25l")
        case _ => false
      },
      s"expected an ANSI-stripped Error event; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("mcp_tool_call emits AssistantToolCall + ToolResult"):
    // A non-ask_user MCP tool round-trips into matching AssistantToolCall +
    // ToolResult events using the dotted `server.tool` naming.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-mcp"}""")
    process.enqueueStdout("""{"type":"turn.started"}""")
    process.enqueueStdout(
      """{"type":"item.started","item":{"id":"i_m","type":"mcp_tool_call","server":"docs","tool":"search","arguments":{"q":"hi"},"result":null,"error":null,"status":"in_progress"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i_m","type":"mcp_tool_call","server":"docs","tool":"search","arguments":{"q":"hi"},"result":{"content":[{"type":"text","text":"page-1"}],"structured_content":null},"error":null,"status":"completed"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i_a","type":"agent_message","text":"done"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.contains(
        ConversationEvent.AssistantToolCall("docs.search", """{"q":"hi"}""")
      ),
      s"expected AssistantToolCall for docs.search; got: $events"
    )
    assert(
      events.contains(
        ConversationEvent.ToolResult(Some("docs.search"), ok = true, "page-1")
      ),
      s"expected matching ToolResult; got: $events"
    )
    val _ = conv.awaitResult()

  convTest(
    "mcp_tool_call ToolResult drops non-text content fragments"
  ):
    // The MCP content array can carry text + image + resource fragments; only
    // text is rendered, and non-text fragments must not leak their wire shape
    // into the ToolResult.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-mx"}""")
    process.enqueueStdout("""{"type":"turn.started"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i_mx","type":"mcp_tool_call","server":"docs","tool":"search","arguments":{},"result":{"content":[{"type":"text","text":"text1"},{"type":"image","data":"…"},{"type":"text","text":"text2"}],"structured_content":null},"error":null,"status":"completed"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i_a","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    val result = events.collectFirst {
      case ConversationEvent.ToolResult(Some("docs.search"), _, content) =>
        content
    }
    assertEquals(result, Some("text1text2"))
    val _ = conv.awaitResult()

  convTest(
    "mcp_tool_call ToolResult surfaces raw JSON when result fails to parse"
  ):
    // For non-standard result shapes the parser falls back to the raw JSON so
    // the diagnostic isn't lost.
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-bad"}""")
    process.enqueueStdout("""{"type":"turn.started"}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i_bad","type":"mcp_tool_call","server":"odd","tool":"do","arguments":{},"result":["this","is","not","an","object"],"error":null,"status":"completed"}}"""
    )
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i_a","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    val result = events.collectFirst {
      case ConversationEvent.ToolResult(Some("odd.do"), _, content) => content
    }
    assert(
      result.exists(_.contains("not")),
      s"expected raw JSON fallback to include the array content; got: $result"
    )
    val _ = conv.awaitResult()

  test(
    "ask_user mcp_tool_call items are suppressed (no echo in event stream)"
  ):
    // The host-side AskUserBridge raises a UserQuestion for the same exchange
    // (covered below); echoing the tool call and answer on top would be noise.
    import ox.supervised
    import ox.channels.BufferCapacity
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val conv = new CodexConversation(
        process,
        askUser = Some(AskUserSession.allocate())
      )

      process.enqueueStdout(
        """{"type":"thread.started","thread_id":"thr-au"}"""
      )
      process.enqueueStdout("""{"type":"turn.started"}""")
      process.enqueueStdout(
        """{"type":"item.started","item":{"id":"i_au","type":"mcp_tool_call","server":"orca","tool":"ask_user","arguments":{"question":"q?"},"result":null,"error":null,"status":"in_progress"}}"""
      )
      process.enqueueStdout(
        """{"type":"item.completed","item":{"id":"i_au","type":"mcp_tool_call","server":"orca","tool":"ask_user","arguments":{"question":"q?"},"result":{"content":[{"type":"text","text":"a"}],"structured_content":null},"error":null,"status":"completed"}}"""
      )
      process.enqueueStdout(
        """{"type":"item.completed","item":{"id":"i_msg","type":"agent_message","text":"hi"}}"""
      )
      process.enqueueStdout(
        """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
      )
      process.closeStdout()
      process.closeStderr()

      val events = conv.events.toList
      assert(
        !events.exists {
          case ConversationEvent.AssistantToolCall("orca.ask_user", _) => true
          case _                                                       => false
        },
        s"ask_user MCP call must be suppressed; got: $events"
      )
      assert(
        !events.exists {
          case ConversationEvent.ToolResult(Some("orca.ask_user"), _, _) =>
            true
          case _ => false
        },
        s"ask_user ToolResult must be suppressed; got: $events"
      )
      val _ = conv.awaitResult()

  test(
    "askUserBridge: questions surface as UserQuestion events; respond unblocks ask"
  ):
    // The drainer converts bridge questions into UserQuestion events and the
    // respond closure unblocks the originating `ask`.
    import ox.{forkUser, supervised}
    import ox.channels.BufferCapacity
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val askUser = AskUserSession.allocate()
      val conv = new CodexConversation(
        process,
        askUser = Some(askUser)
      )
      val bridge = askUser.bridge
      assert(conv.canAskUser, "canAskUser must be true when a bridge is wired")

      val askResult = forkUser:
        bridge.ask("What's your favourite colour?")

      val firstEvent = conv.events.next()
      val (question, respond) = firstEvent match
        case ConversationEvent.UserQuestion(q, r) => (q, r)
        case other => fail(s"expected UserQuestion; got: $other")
      assertEquals(question, "What's your favourite colour?")
      respond("magenta")
      assertEquals(askResult.join(), "magenta")

  convTest("canAskUser is false when no bridge is provided"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)
    assertEquals(conv.canAskUser, false)
    process.closeStdout()
    process.closeStderr()
    val _ = conv.events.toList

  convTest("unknown top-level events are ignored without surfacing"):
    val process = new FakePipedCliProcess()
    val conv = new CodexConversation(process)

    process.enqueueStdout("""{"type":"thread.started","thread_id":"thr-u"}""")
    process.enqueueStdout("""{"type":"some.future.event","data":42}""")
    process.enqueueStdout(
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}"""
    )
    process.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":0,"output_tokens":0,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.exists {
        case ConversationEvent.Error(_) => true
        case _                          => false
      },
      s"unknown top-level events must drop silently; got: $events"
    )
    val _ = conv.awaitResult()
