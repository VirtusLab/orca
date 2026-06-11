package orca.tools.claude

import orca.backend.Dispatch
import orca.llm.{AutoApprove, BackendTag, LlmConfig, Model, SessionId, ToolSet}
class ClaudeArgsTest extends munit.FunSuite:

  private val testSid =
    SessionId[BackendTag.ClaudeCode.type](
      "00000000-0000-0000-0000-000000000000"
    )

  private def streamJson(
      config: LlmConfig,
      dispatch: Dispatch[BackendTag.ClaudeCode.type] = Dispatch.Fresh(testSid)
  ): Seq[String] =
    ClaudeArgs.streamJson(
      config = config,
      systemPromptFile = None,
      dispatch = dispatch
    )

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
      systemPromptFile = Some(file),
      dispatch = Dispatch.Fresh(testSid)
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

  test(
    "ToolSet.ReadOnly maps to --permission-mode plan, overriding autoApprove"
  ):
    // The read-only tier is the planner/reviewer hard restriction —
    // Edit/Write/Bash unavailable, not just non-auto-approved. It wins over
    // `autoApprove` (the agent is verifying claims, not editing).
    val args = streamJson(
      LlmConfig(autoApprove = AutoApprove.All, tools = ToolSet.ReadOnly)
    )
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(!args.contains("bypassPermissions"), args)
    assert(!args.contains("--allowedTools"), args)

  // For now NetworkOnly is plain plan mode; a later step layers its network
  // allowlist on the backend (and strengthens this assertion).
  test(
    "ToolSet.NetworkOnly stays --permission-mode plan with no inline allowlist"
  ):
    val args = streamJson(LlmConfig(tools = ToolSet.NetworkOnly))
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(!args.contains("--allowedTools"), args)

  test("Dispatch.Fresh emits --session-id <uuid>"):
    val args = streamJson(LlmConfig.default, dispatch = Dispatch.Fresh(testSid))
    assert(
      args.containsSlice(Seq("--session-id", SessionId.value(testSid))),
      args
    )
    assert(!args.contains("--resume"), args)

  test("Dispatch.Resume emits --resume <uuid>"):
    val args =
      streamJson(LlmConfig.default, dispatch = Dispatch.Resume(testSid))
    assert(args.containsSlice(Seq("--resume", SessionId.value(testSid))), args)
    assert(!args.contains("--session-id"), args)

  test("--json-schema is emitted when an output schema is supplied"):
    val schema = """{"type":"object"}"""
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None,
      dispatch = Dispatch.Fresh(testSid),
      jsonSchema = Some(schema)
    )
    assert(args.containsSlice(Seq("--json-schema", schema)))

  test("--mcp-config <file> is emitted when supplied"):
    val cfg = os.temp()
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None,
      dispatch = Dispatch.Fresh(testSid),
      mcpConfig = Some(cfg)
    )
    assert(args.containsSlice(Seq("--mcp-config", cfg.toString)))

  test("all mappings compose: model + session + autoApprove + system-prompt"):
    val file = os.temp()
    val args = ClaudeArgs.streamJson(
      config = LlmConfig(
        model = Some(Model("opus-4")),
        autoApprove = AutoApprove.Only(Set("Read"))
      ),
      systemPromptFile = Some(file),
      dispatch = Dispatch.Resume(testSid)
    )
    assert(args.containsSlice(Seq("--model", "opus-4")))
    assert(
      args.containsSlice(Seq("--append-system-prompt-file", file.toString))
    )
    assert(args.containsSlice(Seq("--resume", SessionId.value(testSid))))
    assert(args.containsSlice(Seq("--permission-mode", "acceptEdits")))
    assert(args.containsSlice(Seq("--allowedTools", "Read")))
