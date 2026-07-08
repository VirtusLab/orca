package orca.tools.gemini

import orca.agents.WireSessionId
import orca.events.Usage
import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.backend.{ConversationEvent, ConversationEventConformance}
import orca.subprocess.FakePipedCliProcess
import ox.{Ox, supervised}

class GeminiConversationTest extends munit.FunSuite:

  /** `GeminiConversation` forks its reader/stderr/ask-user workers into the
    * caller's per-turn Ox, so construction needs a `using Ox`. Run each test
    * body in a fresh supervised scope that provides it. Tests managing their
    * own scope (the ask-user ones) stay on plain `test`.
    */
  private def convTest(name: String)(body: Ox ?=> Unit): Unit =
    test(name)(supervised(body))

  /** Minimal terminating tail: a `result` event ends the turn and lets
    * `awaitResult` succeed.
    */
  private def result(input: Long = 0L, output: Long = 0L): String =
    s"""{"type":"result","status":"success","stats":{"input_tokens":$input,"output_tokens":$output}}"""

  convTest(
    "assistant message accumulates into output; init sets session + model"
  ):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout(
      """{"type":"init","session_id":"sess-1","model":"gemini-2.5-pro"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"hello"}"""
    )
    process.enqueueStdout(result(input = 10L, output = 3L))
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
    val Right(r) = conv.awaitResult(): @unchecked
    assertEquals(WireSessionId.value(r.wireId), "sess-1")
    assertEquals(r.output, "hello")
    assertEquals(r.usage, Usage(10L, 3L, None))
    assertEquals(r.model.map(_.name), Some("gemini-2.5-pro"))

  convTest("a user-role message is ignored (prompt echo, not agent output)"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"user","content":"List files"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"model","content":"done"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.contains(ConversationEvent.AssistantTextDelta("List files")),
      s"user message must not become agent output; got: $events"
    )
    val Right(r) = conv.awaitResult(): @unchecked
    assertEquals(r.output, "done")

  convTest("multiple assistant chunks concatenate into the output"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"foo"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"bar"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val Right(r) = conv.awaitResult(): @unchecked
    assertEquals(r.output, "foobar")

  convTest("initialPrompt becomes a UserMessage event before agent output"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process, initialPrompt = "do the thing")

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assertEquals(events.head, ConversationEvent.UserMessage("do the thing"))
    val _ = conv.awaitResult()

  convTest("tool_use + tool_result become AssistantToolCall + ToolResult"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b1","parameters":{"command":"ls"}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b1","status":"success","output":"hello.txt"}"""
    )
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"done"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.contains(
        ConversationEvent.AssistantToolCall("Bash", """{"command":"ls"}""")
      ),
      s"expected AssistantToolCall for Bash; got: $events"
    )
    assert(
      events.contains(
        ConversationEvent.ToolResult(Some("Bash"), ok = true, "hello.txt")
      ),
      s"expected matching ToolResult keyed by name; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("a tool whose name merely contains 'ask_user' is NOT suppressed"):
    // Suppression matches gemini's exact MCP qualification (orca__ask_user),
    // not any name containing the slug — an unrelated tool must still surface.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"ask_user_for_help","tool_id":"x1","parameters":{}}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.AssistantToolCall("ask_user_for_help", _) => true
        case _ => false
      },
      s"a non-MCP tool must not be suppressed; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("tool_result with a non-success status yields ok=false"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b1","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b1","status":"error","output":"boom"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    val tr = events
      .collectFirst { case r: ConversationEvent.ToolResult => r }
      .getOrElse(fail("expected a ToolResult"))
    assertEquals(tr.ok, false)
    val _ = conv.awaitResult()

  convTest("benign gemini stderr chatter is filtered (no Error events)"):
    // Observed on every successful headless run (gemini 0.45.2): a 256-color
    // warning, YOLO-mode notices, a cwd-reset line, and IDE-companion probe
    // chatter — all informational.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStderr(
      "Warning: 256-color support not detected. Using a terminal with at least 256-color support is recommended for a better visual experience."
    )
    process.enqueueStderr(
      "YOLO mode is enabled. All tool calls will be automatically approved."
    )
    process.enqueueStderr("Shell cwd was reset to /some/dir")
    process.enqueueStderr(
      "[ERROR] [IDEClient] Failed to connect to IDE companion extension. Please ensure the extension is running."
    )
    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.exists {
        case ConversationEvent.Error(_) => true
        case _                          => false
      },
      s"benign stderr must not surface as Error events; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("a real stderr line still surfaces as ConversationEvent.Error"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStderr("Error: quota exceeded for project")
    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(m) => m.contains("quota exceeded")
        case _                          => false
      },
      s"a real error must surface; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("stderr strips terminal controls before surfacing as an Error"):
    // Pinning Task 8.4's hoist: pre-hoist, only pi's handleStderr stripped
    // ANSI/terminal control sequences before surfacing stderr as an Error
    // event — codex and gemini did not. StderrPipeline now strips
    // for all three uniformly.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStderr("Error: quota[?25l exceeded[2K now")
    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(m) =>
          m.contains("quota exceeded now") && !m.contains("?25l")
        case _ => false
      },
      s"expected an ANSI-stripped Error; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("error event surfaces as ConversationEvent.Error"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("""{"type":"error","message":"rate limited"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(m) => m.contains("rate limited")
        case _                          => false
      },
      s"expected an Error event; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("malformed JSONL surfaces as Error and the loop continues"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("not json at all")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(m) => m.contains("Failed to parse")
        case _                          => false
      },
      s"expected a parse-error event; got: $events"
    )
    val Right(r) = conv.awaitResult(): @unchecked
    assertEquals(r.output, "ok")

  convTest("clean exit without a result event surfaces as OrcaFlowException"):
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val ex = intercept[OrcaFlowException](conv.awaitResult())
    assert(
      ex.getMessage.contains("result"),
      s"expected the missing-result message; got: ${ex.getMessage}"
    )

  convTest(
    "missing session_id on init surfaces a visible Error and the turn fails loudly"
  ):
    // Task 8.5: session_id is identity-critical. Pre-fix, a missing key
    // silently became Init("") with no exception and no Error event — the
    // wrong session id would only surface indirectly, retries later, at
    // commitAfterDrain's guard. Post-fix, InitWire.session_id is a required
    // wire field, so the malformed init line throws JsonReaderException;
    // ForkedConversation's generic per-line catch turns that into a visible
    // Error event near the cause. With no other line ever settling the turn,
    // it still fails loudly via the existing clean-exit-without-result path.
    val process = new FakePipedCliProcess(initiallyAlive = false)
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","model":"gemini-2.5-pro"}""")
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.exists {
        case ConversationEvent.Error(m) => m.contains("Failed to parse")
        case _                          => false
      },
      s"expected a parse-error Error event near the cause; got: $events"
    )
    val ex = intercept[orca.AgentTurnFailed](conv.awaitResult())
    assert(
      ex.getMessage.contains("result"),
      s"expected the missing-result message; got: ${ex.getMessage}"
    )

  convTest(
    "a message with a missing role is dropped, never treated as assistant prose"
  ):
    // Task 8.5: pre-fix, a missing role's "" default failed the `!= "user"`
    // check and silently landed in the answer as agent output. Post-fix it's
    // typed Role.Unknown and dropped.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("""{"type":"message","content":"stray content"}""")
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.contains(ConversationEvent.AssistantTextDelta("stray content")),
      s"a message with an unknown role must not become agent output; got: $events"
    )
    val Right(r) = conv.awaitResult(): @unchecked
    assertEquals(r.output, "")

  convTest("a result event with a non-success status fails the turn"):
    // gemini's `result` carries a status; a failed turn that still exits 0
    // must not be reported as success. "success" is the documented good
    // token (headless stream-json) — anything else non-empty is a failure.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"partial"}"""
    )
    process.enqueueStdout(
      """{"type":"result","status":"error","stats":{"input_tokens":1,"output_tokens":1}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    // The streamed message opened a turn; the base funnel auto-closes it when
    // the failing `result` settles via `failWith`, so even a failed turn is
    // grammar-terminated (see the contract) — completedNormally.
    ConversationEventConformance.assertGrammar(events, completedNormally = true)
    val ex = intercept[orca.AgentTurnFailed](conv.awaitResult())
    assert(
      ex.getMessage.contains("error"),
      s"expected the failing status in the message; got: ${ex.getMessage}"
    )

  convTest("a result event with no status is treated as success"):
    // The status field is optional; an absent/empty status is success, not a
    // failed turn.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"done"}"""
    )
    process.enqueueStdout(
      """{"type":"result","stats":{"input_tokens":1,"output_tokens":1}}"""
    )
    process.closeStdout()
    process.closeStderr()

    val _ = conv.events.toList
    val Right(r) = conv.awaitResult(): @unchecked
    assertEquals(r.output, "done")

  convTest("interleaved tool calls are each keyed back to their own name"):
    // Two tool calls complete out of order (B before A); each tool_result,
    // which carries only the id, must resolve to the right tool_name.
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Read","tool_id":"a","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b","parameters":{}}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"b","status":"success","output":"ls-out"}"""
    )
    process.enqueueStdout(
      """{"type":"tool_result","tool_id":"a","status":"success","output":"file-out"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      events.contains(
        ConversationEvent.ToolResult(Some("Bash"), ok = true, "ls-out")
      ),
      s"tool_result b must key to Bash; got: $events"
    )
    assert(
      events.contains(
        ConversationEvent.ToolResult(Some("Read"), ok = true, "file-out")
      ),
      s"tool_result a must key to Read; got: $events"
    )
    val _ = conv.awaitResult()

  convTest(
    "cancel surfaces as Left(OrcaInteractiveCancelled) from awaitResult"
  ):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)
    conv.cancel()
    conv.awaitResult() match
      case Left(_: OrcaInteractiveCancelled) => ()
      case other =>
        fail(s"expected Left(OrcaInteractiveCancelled), got: $other")
    assertEquals(process.sigIntCount, 1)

  convTest("unknown top-level events are ignored without surfacing"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)

    process.enqueueStdout("""{"type":"init","session_id":"s"}""")
    process.enqueueStdout("""{"type":"some.future.event","data":42}""")
    process.enqueueStdout(
      """{"type":"message","role":"assistant","content":"ok"}"""
    )
    process.enqueueStdout(result())
    process.closeStdout()
    process.closeStderr()

    val events = conv.events.toList
    assert(
      !events.exists {
        case ConversationEvent.Error(_) => true
        case _                          => false
      },
      s"unknown events must drop silently; got: $events"
    )
    val _ = conv.awaitResult()

  convTest("canAskUser is false when no bridge is provided"):
    val process = new FakePipedCliProcess()
    val conv = new GeminiConversation(process)
    assertEquals(conv.canAskUser, false)
    process.closeStdout()
    process.closeStderr()
    val _ = conv.events.toList

  test("ask_user tool_use/tool_result are suppressed (no echo)"):
    import ox.supervised
    import ox.channels.BufferCapacity
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val conv = new GeminiConversation(
        process,
        askUser = Some(AskUserSession.allocate())
      )

      process.enqueueStdout("""{"type":"init","session_id":"s"}""")
      process.enqueueStdout(
        """{"type":"tool_use","tool_name":"orca__ask_user","tool_id":"au1","parameters":{"question":"q?"}}"""
      )
      process.enqueueStdout(
        """{"type":"tool_result","tool_id":"au1","status":"success","output":"a"}"""
      )
      process.enqueueStdout(
        """{"type":"message","role":"assistant","content":"hi"}"""
      )
      process.enqueueStdout(result())
      process.closeStdout()
      process.closeStderr()

      val events = conv.events.toList
      assert(
        !events.exists {
          case ConversationEvent.AssistantToolCall(n, _) =>
            n.contains("ask_user")
          case ConversationEvent.ToolResult(n, _, _) =>
            n.exists(_.contains("ask_user"))
          case _ => false
        },
        s"ask_user exchange must be suppressed; got: $events"
      )
      val _ = conv.awaitResult()

  test("askUserBridge questions surface as UserQuestion; respond unblocks"):
    import ox.{forkUser, supervised}
    import ox.channels.BufferCapacity
    import orca.backend.mcp.AskUserSession
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val process = new FakePipedCliProcess()
      val askUser = AskUserSession.allocate()
      val conv = new GeminiConversation(process, askUser = Some(askUser))
      val bridge = askUser.bridge
      assert(conv.canAskUser, "canAskUser must be true when a bridge is wired")

      val askResult = forkUser:
        bridge.ask("What's your favourite colour?")

      val (question, respond) = conv.events.next() match
        case ConversationEvent.UserQuestion(q, r) => (q, r)
        case other => fail(s"expected UserQuestion; got: $other")
      assertEquals(question, "What's your favourite colour?")
      respond("magenta")
      assertEquals(askResult.join(), "magenta")
