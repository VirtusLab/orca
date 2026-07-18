package orca.tools.codex

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Model,
  WireSessionId,
  ToolSet
}
import orca.testkit.TempDirs
class CodexArgsTest extends munit.FunSuite:

  test("exec emits codex exec --json with the prompt as the trailing arg"):
    val args = CodexArgs.exec(
      prompt = "summarize",
      config = AgentConfig(),
      outputSchemaFile = None,
      workDir = os.pwd
    )
    assertEquals(args.take(3), Seq("codex", "exec", "--json"))
    assertEquals(args.last, "summarize")

  test("exec passes --model when AgentConfig.model is set"):
    val args = CodexArgs.exec(
      prompt = "x",
      config = AgentConfig().copy(model = Some(Model("gpt-5.4-mini"))),
      outputSchemaFile = None,
      workDir = os.pwd
    )
    assert(args.containsSlice(Seq("--model", "gpt-5.4-mini")))

  test("exec passes -C <workDir>"):
    val workDir = TempDirs.dir()
    val args = CodexArgs.exec(
      prompt = "x",
      config = AgentConfig(),
      outputSchemaFile = None,
      workDir = workDir
    )
    assert(args.containsSlice(Seq("-C", workDir.toString)))

  test("exec includes --skip-git-repo-check"):
    val args = CodexArgs.exec("x", AgentConfig(), None, os.pwd)
    assert(args.contains("--skip-git-repo-check"))

  test("exec passes --output-schema <file> when supplied"):
    val schemaFile = os.temp() / "schema.json"
    val args =
      CodexArgs.exec("x", AgentConfig(), Some(schemaFile), os.pwd)
    assert(args.containsSlice(Seq("--output-schema", schemaFile.toString)))

  test(
    "AutoApprove.All maps to --dangerously-bypass-approvals-and-sandbox"
  ):
    val args = CodexArgs.exec(
      "x",
      AgentConfig().copy(autoApprove = AutoApprove.All),
      None,
      os.pwd
    )
    assert(args.contains("--dangerously-bypass-approvals-and-sandbox"))
    assert(!args.contains("--full-auto"))

  test("AutoApprove.Only maps to --full-auto"):
    val args = CodexArgs.exec(
      "x",
      AgentConfig().copy(autoApprove = AutoApprove.Only(Set("Bash"))),
      None,
      os.pwd
    )
    assert(args.contains("--full-auto"))
    assert(!args.contains("--dangerously-bypass-approvals-and-sandbox"))

  test(
    "ToolSet.ReadOnly maps to --sandbox read-only and overrides autoApprove"
  ):
    // Pins the gate used by `.withReadOnly` callers — reviewers,
    // ReviewerSelector.agentDriven, lint, Plan.autonomous.from. Without
    // this mapping codex's reviewers inherit the base tool's permissions
    // and could edit files during a review turn.
    val args = CodexArgs.exec(
      "x",
      AgentConfig().copy(
        tools = ToolSet.ReadOnly,
        autoApprove = AutoApprove.All
      ),
      None,
      os.pwd
    )
    assert(args.containsSlice(Seq("--sandbox", "read-only")), args.toString)
    assert(!args.contains("--dangerously-bypass-approvals-and-sandbox"))
    assert(!args.contains("--full-auto"))

  test("ToolSet.NetworkOnly uses --full-auto + network override before exec"):
    // codex has no read-only-with-network sandbox: network needs
    // workspace-write (via --full-auto), enabled by the global `-c` override
    // which must precede the `exec` subcommand.
    val args = CodexArgs.exec(
      "x",
      AgentConfig().copy(tools = ToolSet.NetworkOnly),
      None,
      os.pwd
    )
    assert(args.contains("--full-auto"), args.toString)
    assert(!args.containsSlice(Seq("--sandbox", "read-only")), args.toString)
    val execIdx = args.indexOf("exec")
    val cIdx = args.indexOf("sandbox_workspace_write.network_access=true")
    assert(cIdx >= 0 && cIdx < execIdx, args.toString)

  test(
    "exec emits -c mcp_servers.orca.{url,tool_timeout_sec} when an MCP url is supplied"
  ):
    // The `-c` overrides register the ask_user MCP server for the codex
    // invocation and raise its tool timeout from codex's 60s default to one
    // hour, so a slow human answering doesn't trigger a duplicate follow-up.
    val args = CodexArgs.exec(
      "x",
      AgentConfig(),
      None,
      os.pwd,
      mcpServerUrl = Some("http://127.0.0.1:9876/mcp")
    )
    // -c overrides must precede the `exec` subcommand so codex parses
    // them as top-level config, not as exec-specific flags.
    val execIdx = args.indexOf("exec")
    val cValues = args.iterator
      .zip(args.iterator.drop(1))
      .zipWithIndex
      .collect { case (("-c", v), i) if i < execIdx => v }
      .toList
    assert(
      cValues.contains("""mcp_servers.orca.url="http://127.0.0.1:9876/mcp""""),
      s"expected url -c override; got: $cValues"
    )
    assert(
      cValues.contains("mcp_servers.orca.tool_timeout_sec=3600"),
      s"expected tool_timeout_sec -c override; got: $cValues"
    )

  test("exec omits -c mcp_servers when no MCP url is supplied"):
    val args = CodexArgs.exec("x", AgentConfig(), None, os.pwd)
    assert(
      !args.exists(_.startsWith("mcp_servers.")),
      s"args should not mention mcp_servers; got: $args"
    )

  test("execResume builds codex exec resume <id> [...] <prompt>"):
    val sid = WireSessionId[BackendTag.Codex.type]("019dc-thread")
    val args = CodexArgs.execResume(
      sid,
      "next step",
      AgentConfig()
    )
    assertEquals(args.take(4), Seq("codex", "exec", "resume", "--json"))
    assert(args.contains("019dc-thread"))
    assertEquals(args.last, "next step")

  test("execResume omits -C and --output-schema (codex doesn't accept them)"):
    val sid = WireSessionId[BackendTag.Codex.type]("sid")
    val args = CodexArgs.execResume(sid, "x", AgentConfig())
    assert(!args.contains("-C"))
    assert(!args.contains("--output-schema"))

  test(
    "execResume omits --sandbox/--full-auto (exec resume rejects them; inherited)"
  ):
    // Regression: `codex exec resume` errors with "unexpected argument
    // '--sandbox'"; the resumed session inherits its sandbox from creation.
    val sid = WireSessionId[BackendTag.Codex.type]("sid")
    val readOnly =
      CodexArgs.execResume(
        sid,
        "x",
        AgentConfig().copy(tools = ToolSet.ReadOnly)
      )
    assert(!readOnly.contains("--sandbox"), readOnly)
    val networkOnly =
      CodexArgs.execResume(
        sid,
        "x",
        AgentConfig().copy(tools = ToolSet.NetworkOnly)
      )
    assert(!networkOnly.contains("--full-auto"), networkOnly)
    val fullOnly = CodexArgs.execResume(
      sid,
      "x",
      AgentConfig().copy(autoApprove = AutoApprove.Only(Set("Bash")))
    )
    assert(!fullOnly.contains("--full-auto"), fullOnly)

  test(
    "execResume keeps --dangerously-bypass-approvals-and-sandbox (Full + All)"
  ):
    // The one sandbox flag `exec resume` accepts; re-asserted each turn to keep
    // approvals off for an auto-approve-all coder session.
    val sid = WireSessionId[BackendTag.Codex.type]("sid")
    val args = CodexArgs.execResume(
      sid,
      "x",
      AgentConfig().copy(autoApprove = AutoApprove.All)
    )
    assert(args.contains("--dangerously-bypass-approvals-and-sandbox"), args)

  test("execResume propagates --model when AgentConfig.model is set"):
    val sid = WireSessionId[BackendTag.Codex.type]("sid")
    val args = CodexArgs.execResume(
      sid,
      "x",
      AgentConfig().copy(model = Some(Model("gpt-5.4-mini")))
    )
    assert(args.containsSlice(Seq("--model", "gpt-5.4-mini")))
