package orca.tools.claude

import orca.backend.Dispatch
import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Model,
  WireSessionId,
  ToolSet
}
class ClaudeArgsTest extends munit.FunSuite:

  private val testSid =
    WireSessionId[BackendTag.ClaudeCode.type](
      "00000000-0000-0000-0000-000000000000"
    )

  private def streamJson(
      config: AgentConfig,
      dispatch: Dispatch[BackendTag.ClaudeCode.type] =
        Dispatch.Fresh(Some(testSid)),
      networkTools: Seq[String] = Seq.empty
  ): Seq[String] =
    ClaudeArgs.streamJson(
      config = config,
      systemPromptFile = None,
      dispatch = dispatch,
      networkTools = networkTools
    )

  test("stream-json shape: --print, --input/--output-format stream-json, etc."):
    val args = streamJson(AgentConfig())
    assert(args.contains("--print"))
    assert(args.containsSlice(Seq("--input-format", "stream-json")))
    assert(args.containsSlice(Seq("--output-format", "stream-json")))
    assert(args.contains("--verbose"))
    assert(args.contains("--include-partial-messages"))

  test("model flag is emitted when AgentConfig.model is set"):
    val args = streamJson(AgentConfig(model = Some(Model("sonnet-4"))))
    assert(args.containsSlice(Seq("--model", "sonnet-4")))

  test("model flag is absent when AgentConfig.model is None"):
    assert(!streamJson(AgentConfig()).contains("--model"))

  test("system-prompt-file flag is emitted when a file is supplied"):
    val file = os.temp(contents = "content")
    val args = ClaudeArgs.streamJson(
      config = AgentConfig(),
      systemPromptFile = Some(file),
      dispatch = Dispatch.Fresh(Some(testSid))
    )
    assert(
      args.containsSlice(Seq("--append-system-prompt-file", file.toString))
    )

  test("AutoApprove.All maps to --permission-mode bypassPermissions"):
    val args = streamJson(AgentConfig(autoApprove = AutoApprove.All))
    assert(args.containsSlice(Seq("--permission-mode", "bypassPermissions")))
    assert(!args.contains("--allowedTools"))

  test(
    "AutoApprove.Only(tools) maps to sorted --allowedTools in default mode"
  ):
    // Honest Only (finding 2.1): default permission mode, only the listed
    // tools pre-approved — no acceptEdits blanket-approving every edit.
    val args = streamJson(
      AgentConfig(autoApprove =
        AutoApprove.Only(Set("Zeta", "Alpha", "Middle"))
      )
    )
    assert(args.containsSlice(Seq("--allowedTools", "Alpha,Middle,Zeta")))
    assert(!args.contains("acceptEdits"), args)
    assert(!args.contains("bypassPermissions"), args)
    assert(!args.contains("--permission-mode"), args)

  test("AutoApprove.Only(empty) emits no permission flags"):
    // Nothing pre-approved: default mode, no allowlist — every tool prompts.
    val args =
      streamJson(AgentConfig(autoApprove = AutoApprove.Only(Set.empty)))
    assert(!args.contains("--permission-mode"), args)
    assert(!args.contains("--allowedTools"), args)

  test(
    "ToolSet.ReadOnly maps to --permission-mode plan, overriding autoApprove"
  ):
    // The read-only tier is the planner/reviewer hard restriction —
    // Edit/Write/Bash unavailable, not just non-auto-approved. It wins over
    // `autoApprove` (the agent is verifying claims, not editing).
    val args = streamJson(
      AgentConfig(autoApprove = AutoApprove.All, tools = ToolSet.ReadOnly)
    )
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(!args.contains("bypassPermissions"), args)
    assert(!args.contains("--allowedTools"), args)

  test(
    "ToolSet.NetworkOnly layers networkTools onto plan mode via --allowedTools"
  ):
    val args = streamJson(
      AgentConfig(tools = ToolSet.NetworkOnly),
      networkTools = Seq("WebFetch", "Bash(gh api:*)")
    )
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(
      args.containsSlice(Seq("--allowedTools", "WebFetch,Bash(gh api:*)")),
      args
    )

  test("ToolSet.NetworkOnly with no networkTools stays plain plan mode"):
    val args = streamJson(AgentConfig(tools = ToolSet.NetworkOnly))
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(!args.contains("--allowedTools"), args)

  test("ToolSet.ReadOnly never emits networkTools even when supplied"):
    // Reviewers/triage use ReadOnly and must stay network-free.
    val args = streamJson(
      AgentConfig(tools = ToolSet.ReadOnly),
      networkTools = Seq("WebFetch")
    )
    assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
    assert(!args.contains("--allowedTools"), args)

  test("Dispatch.Fresh emits --session-id <uuid>"):
    val args =
      streamJson(AgentConfig(), dispatch = Dispatch.Fresh(Some(testSid)))
    assert(
      args.containsSlice(Seq("--session-id", WireSessionId.value(testSid))),
      args
    )
    assert(!args.contains("--resume"), args)

  test("Dispatch.Resume emits --resume <uuid>"):
    val args =
      streamJson(AgentConfig(), dispatch = Dispatch.Resume(testSid))
    assert(
      args.containsSlice(Seq("--resume", WireSessionId.value(testSid))),
      args
    )
    assert(!args.contains("--session-id"), args)

  test("--json-schema is emitted when an output schema is supplied"):
    val schema = """{"type":"object"}"""
    val args = ClaudeArgs.streamJson(
      config = AgentConfig(),
      systemPromptFile = None,
      dispatch = Dispatch.Fresh(Some(testSid)),
      jsonSchema = Some(schema)
    )
    assert(args.containsSlice(Seq("--json-schema", schema)))

  test("--mcp-config <file> is emitted when supplied"):
    val cfg = os.temp()
    val args = ClaudeArgs.streamJson(
      config = AgentConfig(),
      systemPromptFile = None,
      dispatch = Dispatch.Fresh(Some(testSid)),
      mcpConfig = Some(cfg)
    )
    assert(args.containsSlice(Seq("--mcp-config", cfg.toString)))

  test("all mappings compose: model + session + autoApprove + system-prompt"):
    val file = os.temp()
    val args = ClaudeArgs.streamJson(
      config = AgentConfig(
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
    assert(args.containsSlice(Seq("--resume", WireSessionId.value(testSid))))
    assert(args.containsSlice(Seq("--allowedTools", "Read")))
    assert(!args.contains("acceptEdits"), args)
    assert(!args.contains("--permission-mode"), args)
