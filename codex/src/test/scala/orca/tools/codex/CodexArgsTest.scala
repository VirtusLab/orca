package orca.tools.codex

import orca.llm.{AutoApprove, BackendTag, LlmConfig, SessionId}
class CodexArgsTest extends munit.FunSuite:

  test("exec emits codex exec --json with the prompt as the trailing arg"):
    val args = CodexArgs.exec(
      prompt = "summarize",
      config = LlmConfig.default,
      outputSchemaFile = None,
      workDir = os.pwd
    )
    assertEquals(args.take(3), Seq("codex", "exec", "--json"))
    assertEquals(args.last, "summarize")

  test("exec passes --model when LlmConfig.model is set"):
    val args = CodexArgs.exec(
      prompt = "x",
      config = LlmConfig.default.copy(model = Some("gpt-5.4-mini")),
      outputSchemaFile = None,
      workDir = os.pwd
    )
    assert(args.containsSlice(Seq("--model", "gpt-5.4-mini")))

  test("exec passes -C <workDir>"):
    val workDir = os.temp.dir()
    val args = CodexArgs.exec(
      prompt = "x",
      config = LlmConfig.default,
      outputSchemaFile = None,
      workDir = workDir
    )
    assert(args.containsSlice(Seq("-C", workDir.toString)))

  test("exec includes --skip-git-repo-check"):
    val args = CodexArgs.exec("x", LlmConfig.default, None, os.pwd)
    assert(args.contains("--skip-git-repo-check"))

  test("exec passes --output-schema <file> when supplied"):
    val schemaFile = os.temp() / "schema.json"
    val args = CodexArgs.exec("x", LlmConfig.default, Some(schemaFile), os.pwd)
    assert(args.containsSlice(Seq("--output-schema", schemaFile.toString)))

  test(
    "AutoApprove.All maps to --dangerously-bypass-approvals-and-sandbox"
  ):
    val args = CodexArgs.exec(
      "x",
      LlmConfig.default.copy(autoApprove = AutoApprove.All),
      None,
      os.pwd
    )
    assert(args.contains("--dangerously-bypass-approvals-and-sandbox"))
    assert(!args.contains("--full-auto"))

  test("AutoApprove.Only maps to --full-auto"):
    val args = CodexArgs.exec(
      "x",
      LlmConfig.default.copy(autoApprove = AutoApprove.Only(Set("Bash"))),
      None,
      os.pwd
    )
    assert(args.contains("--full-auto"))
    assert(!args.contains("--dangerously-bypass-approvals-and-sandbox"))

  test("execResume builds codex exec resume <id> [...] <prompt>"):
    val sid = SessionId[BackendTag.Codex.type]("019dc-thread")
    val args = CodexArgs.execResume(
      sid,
      "next step",
      LlmConfig.default
    )
    assertEquals(args.take(4), Seq("codex", "exec", "resume", "--json"))
    assert(args.contains("019dc-thread"))
    assertEquals(args.last, "next step")

  test("execResume omits -C and --output-schema (codex doesn't accept them)"):
    val sid = SessionId[BackendTag.Codex.type]("sid")
    val args = CodexArgs.execResume(sid, "x", LlmConfig.default)
    assert(!args.contains("-C"))
    assert(!args.contains("--output-schema"))

  test("execResume propagates --model when LlmConfig.model is set"):
    val sid = SessionId[BackendTag.Codex.type]("sid")
    val args = CodexArgs.execResume(
      sid,
      "x",
      LlmConfig.default.copy(model = Some("gpt-5.4-mini"))
    )
    assert(args.containsSlice(Seq("--model", "gpt-5.4-mini")))
