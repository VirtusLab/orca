package orca.tools.gemini

import orca.llm.{AutoApprove, BackendTag, LlmConfig, Model, SessionId, ToolSet}

class GeminiArgsTest extends munit.FunSuite:

  test("headless emits gemini -p <prompt> --output-format stream-json"):
    val args = GeminiArgs.headless("summarize", LlmConfig.default)
    assertEquals(args.head, "gemini")
    assert(
      args.containsSlice(Seq("--output-format", "stream-json")),
      args.toString
    )
    assert(args.containsSlice(Seq("-p", "summarize")), args.toString)

  test("headless passes --skip-trust so headless runs in an untrusted dir"):
    // Without it gemini refuses to run in a non-trusted folder (exit 55) and
    // silently overrides --approval-mode back to "default". orca always drives
    // a working dir the agent is meant to operate in, so trust is unconditional
    // — the analog of codex's --skip-git-repo-check.
    val args = GeminiArgs.headless("x", LlmConfig.default)
    assert(args.contains("--skip-trust"), args.toString)

  test("headless passes --model when LlmConfig.model is set"):
    val args = GeminiArgs.headless(
      "x",
      LlmConfig.default.copy(model = Some(Model("gemini-2.5-flash")))
    )
    assert(args.containsSlice(Seq("--model", "gemini-2.5-flash")))

  test("AutoApprove.All maps to --approval-mode yolo"):
    val args = GeminiArgs.headless(
      "x",
      LlmConfig.default.copy(autoApprove = AutoApprove.All)
    )
    assert(args.containsSlice(Seq("--approval-mode", "yolo")), args.toString)

  test("AutoApprove.Only widens to --approval-mode yolo"):
    // Gemini has no per-tool CLI allowlist; Only(_) widens to yolo so
    // autonomous (headless) turns actually progress instead of blocking on
    // an approval prompt no one can answer. See ADR 0015.
    val args = GeminiArgs.headless(
      "x",
      LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set("Bash")))
    )
    assert(args.containsSlice(Seq("--approval-mode", "yolo")), args.toString)
    assert(!args.contains("plan"))

  test(
    "ToolSet.ReadOnly maps to --approval-mode plan and overrides autoApprove"
  ):
    val args = GeminiArgs.headless(
      "x",
      LlmConfig.default.copy(
        tools = ToolSet.ReadOnly,
        autoApprove = AutoApprove.All
      )
    )
    assert(args.containsSlice(Seq("--approval-mode", "plan")), args.toString)
    assert(!args.containsSlice(Seq("--approval-mode", "yolo")))

  test("ToolSet.NetworkOnly stays in plan mode and pre-approves web_fetch"):
    val args =
      GeminiArgs.headless(
        "x",
        LlmConfig.default.copy(tools = ToolSet.NetworkOnly)
      )
    assert(args.containsSlice(Seq("--approval-mode", "plan")), args.toString)
    assert(
      args.containsSlice(Seq("--allowed-tools", "web_fetch")),
      args.toString
    )

  test("resume builds gemini ... --resume <id> with the prompt"):
    val sid = SessionId[BackendTag.Gemini.type]("uuid-123")
    val args = GeminiArgs.resume(sid, "next step", LlmConfig.default)
    assertEquals(args.head, "gemini")
    assert(args.containsSlice(Seq("--resume", "uuid-123")), args.toString)
    assert(args.containsSlice(Seq("-p", "next step")), args.toString)

  test("resume propagates --model when LlmConfig.model is set"):
    val sid = SessionId[BackendTag.Gemini.type]("sid")
    val args = GeminiArgs.resume(
      sid,
      "x",
      LlmConfig.default.copy(model = Some(Model("gemini-2.5-pro")))
    )
    assert(args.containsSlice(Seq("--model", "gemini-2.5-pro")))
