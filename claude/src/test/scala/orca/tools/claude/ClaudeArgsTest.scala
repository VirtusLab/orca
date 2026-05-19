package orca.tools.claude

import orca.{AutoApprove, BackendTag, LlmConfig, SessionId}

class ClaudeArgsTest extends munit.FunSuite:

  private def headless(config: LlmConfig): Seq[String] =
    ClaudeArgs.headless(
      prompt = "task",
      config = config,
      systemPromptFile = None
    )

  test("headless embeds -p and --output-format json"):
    val args = headless(LlmConfig.default)
    assert(args.containsSlice(Seq("claude", "-p", "task")))
    assert(args.containsSlice(Seq("--output-format", "json")))

  test("model flag is emitted when LlmConfig.model is set"):
    val args = headless(LlmConfig(model = Some("sonnet-4")))
    assert(args.containsSlice(Seq("--model", "sonnet-4")))

  test("model flag is absent when LlmConfig.model is None"):
    assert(!headless(LlmConfig.default).contains("--model"))

  test("system-prompt-file flag is emitted when a file is supplied"):
    val file = os.temp(contents = "content")
    val args = ClaudeArgs.headless(
      prompt = "task",
      config = LlmConfig.default,
      systemPromptFile = Some(file)
    )
    assert(
      args.containsSlice(Seq("--append-system-prompt-file", file.toString))
    )

  test("AutoApprove.All maps to --permission-mode bypassPermissions"):
    val args = headless(LlmConfig(autoApprove = AutoApprove.All))
    assert(args.containsSlice(Seq("--permission-mode", "bypassPermissions")))
    assert(!args.contains("--allowedTools"))

  test("AutoApprove.Only(tools) maps to acceptEdits + sorted --allowedTools"):
    val args = headless(
      LlmConfig(autoApprove = AutoApprove.Only(Set("Zeta", "Alpha", "Middle")))
    )
    assert(args.containsSlice(Seq("--permission-mode", "acceptEdits")))
    assert(args.containsSlice(Seq("--allowedTools", "Alpha,Middle,Zeta")))

  test("AutoApprove.Only(empty) maps to acceptEdits with no --allowedTools"):
    val args = headless(LlmConfig(autoApprove = AutoApprove.Only(Set.empty)))
    assert(args.containsSlice(Seq("--permission-mode", "acceptEdits")))
    assert(!args.contains("--allowedTools"))

  test("--resume <id> is emitted when a session id is supplied"):
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-abc")
    val args = ClaudeArgs.headless(
      prompt = "task",
      config = LlmConfig.default,
      systemPromptFile = None,
      resume = Some(sid)
    )
    assert(args.containsSlice(Seq("--resume", "sess-abc")))

  test(
    "streamJson emits --input-format stream-json and --include-partial-messages"
  ):
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None
    )
    assert(args.containsSlice(Seq("--input-format", "stream-json")))
    assert(args.containsSlice(Seq("--output-format", "stream-json")))
    assert(args.contains("--include-partial-messages"))
    assert(args.contains("--print"))

  test("streamJson with --resume carries the session id through"):
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-resume")
    val args = ClaudeArgs.streamJson(
      config = LlmConfig.default,
      systemPromptFile = None,
      resume = Some(sid)
    )
    assert(args.containsSlice(Seq("--resume", "sess-resume")))

  test("all mappings compose: model + resume + autoApprove + system-prompt"):
    val file = os.temp()
    val sid = SessionId[BackendTag.ClaudeCode.type]("sess-xyz")
    val args = ClaudeArgs.headless(
      prompt = "task",
      config = LlmConfig(
        model = Some("opus-4"),
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
