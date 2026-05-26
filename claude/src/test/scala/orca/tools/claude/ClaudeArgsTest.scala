package orca.tools.claude

import orca.llm.{AutoApprove, BackendTag, LlmConfig, Model, SessionId}
class ClaudeArgsTest extends munit.FunSuite:

  private def streamJson(config: LlmConfig): Seq[String] =
    ClaudeArgs.streamJson(config = config, systemPromptFile = None)

  test("stream-json shape: --print, --input/--output-format stream-json, etc."):
    val args = streamJson(LlmConfig.default)
    assert(args.contains("--print"))
    assert(args.containsSlice(Seq("--input-format", "stream-json")))
    assert(args.containsSlice(Seq("--output-format", "stream-json")))
    assert(args.contains("--verbose"))
    assert(args.contains("--include-partial-messages"))

  test("model flag is emitted when LlmConfig.model is set"):
    val args = streamJson(LlmConfig(model = Some(Model("sonnet-4"))))
    assert(args.containsSlice(Seq("--model", "sonnet-4")))

  test("model flag is absent when LlmConfig.model is None"):
    assert(!streamJson(LlmConfig.default).contains("--model"))

  test("system-prompt-file flag is emitted when a file is supplied"):
    val file = os.temp(contents = "content")
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = Some(file)
    )
    assert(
      args.containsSlice(Seq("--append-system-prompt-file", file.toString))
    )

  test("AutoApprove.All maps to --permission-mode bypassPermissions"):
    val args = streamJson(LlmConfig(autoApprove = AutoApprove.All))
    assert(args.containsSlice(Seq("--permission-mode", "bypassPermissions")))
    assert(!args.contains("--allowedTools"))

  test("AutoApprove.Only(tools) maps to acceptEdits + sorted --allowedTools"):
    val args = streamJson(
      LlmConfig(autoApprove = AutoApprove.Only(Set("Zeta", "Alpha", "Middle")))
    )
    assert(args.containsSlice(Seq("--permission-mode", "acceptEdits")))
    assert(args.containsSlice(Seq("--allowedTools", "Alpha,Middle,Zeta")))

  test("AutoApprove.Only(empty) maps to acceptEdits with no --allowedTools"):
    val args =
      streamJson(LlmConfig(autoApprove = AutoApprove.Only(Set.empty)))
    assert(args.containsSlice(Seq("--permission-mode", "acceptEdits")))
    assert(!args.contains("--allowedTools"))

  test("readOnly=true maps to --permission-mode plan, overriding autoApprove"):
    // `readOnly` is the planner's hard restriction — Edit/Write/Bash must be
    // unavailable, not just non-auto-approved. It wins over `autoApprove`
    // because the use case is "the agent is verifying claims, not editing".
    val args =
      streamJson(LlmConfig(autoApprove = AutoApprove.All, readOnly = true))
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(!args.contains("bypassPermissions"), args)
    assert(!args.contains("--allowedTools"), args)

  test("--resume <id> is emitted when a session id is supplied"):
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-abc")
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None,
      resume = Some(sid)
    )
    assert(args.containsSlice(Seq("--resume", "sess-abc")))

  test("--json-schema is emitted when an output schema is supplied"):
    val schema = """{"type":"object"}"""
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None,
      jsonSchema = Some(schema)
    )
    assert(args.containsSlice(Seq("--json-schema", schema)))

  test("--mcp-config <file> is emitted when supplied"):
    val cfg = os.temp()
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None,
      mcpConfig = Some(cfg)
    )
    assert(args.containsSlice(Seq("--mcp-config", cfg.toString)))

  test("all mappings compose: model + resume + autoApprove + system-prompt"):
    val file = os.temp()
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-xyz")
    val args = ClaudeArgs.streamJson(
      config = LlmConfig(
        model = Some(Model("opus-4")),
        autoApprove = AutoApprove.Only(Set("Read"))
      ),
      systemPromptFile = Some(file),
      resume = Some(sid)
    )
    assert(args.containsSlice(Seq("--model", "opus-4")))
    assert(
      args.containsSlice(Seq("--append-system-prompt-file", file.toString))
    )
    assert(args.containsSlice(Seq("--resume", "sess-xyz")))
    assert(args.containsSlice(Seq("--permission-mode", "acceptEdits")))
    assert(args.containsSlice(Seq("--allowedTools", "Read")))
