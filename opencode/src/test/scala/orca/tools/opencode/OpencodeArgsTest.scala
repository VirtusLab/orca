package orca.tools.opencode

import orca.backend.{SessionMode, SystemPromptComposer}
import orca.agents.{AutoApprove, AgentConfig, Model, ToolSet}

class OpencodeArgsTest extends munit.FunSuite:

  private val autonomous = SessionMode.Autonomous
  private val interactive = SessionMode.Interactive("display")

  test("serve uses a random port, WARN logs, and no --pure"):
    val args = OpencodeArgs.serve()
    assertEquals(
      args,
      Seq("opencode", "serve", "--port", "0", "--log-level", "WARN")
    )
    assert(!args.contains("--pure"))

  test("serve renders an explicit port"):
    assert(OpencodeArgs.serve(port = 4096).containsSlice(Seq("--port", "4096")))

  test("serve prefixes a custom launcher before the serve args"):
    assertEquals(
      OpencodeArgs.serve(OpencodeLauncher.ollama("qwen3-coder")),
      Seq(
        "ollama",
        "launch",
        "opencode",
        "--model",
        "qwen3-coder",
        "--",
        "serve",
        "--port",
        "0",
        "--log-level",
        "WARN"
      )
    )

  test("message splits the model into provider/id"):
    val body = OpencodeArgs.message(
      AgentConfig.default
        .copy(model = Some(Model("anthropic/claude-opus-4-8"))),
      "hi",
      outputSchema = None,
      interactive
    )
    assertEquals(
      body.model,
      Some(OpencodeApi.ModelRef("anthropic", "claude-opus-4-8"))
    )
    assertEquals(body.parts, List(OpencodeApi.MessagePart("text", "hi")))

  test("message splits a multi-slash (self-hosted) model on the first / only"):
    val body = OpencodeArgs.message(
      AgentConfig.default
        .copy(model = Some(Model("lmstudio/google/gemma-3n-e4b"))),
      "hi",
      None,
      interactive
    )
    assertEquals(
      body.model,
      Some(OpencodeApi.ModelRef("lmstudio", "google/gemma-3n-e4b"))
    )

  test("message omits the model when config has none (server default)"):
    assertEquals(
      OpencodeArgs.message(AgentConfig.default, "hi", None, interactive).model,
      None
    )

  test("message carries the composed system prompt (RuntimeOwnsGit rule)"):
    val body =
      OpencodeArgs.message(AgentConfig.default, "hi", None, interactive)
    assertEquals(body.system, Some(SystemPromptComposer.RuntimeOwnsGit))

  test("structured turn sets format=json_schema with the schema verbatim"):
    val body = OpencodeArgs.message(
      AgentConfig.default,
      "hi",
      outputSchema = Some("""{"type":"object"}"""),
      interactive
    )
    assertEquals(body.format.map(_.`type`), Some("json_schema"))
    assertEquals(body.format.map(_.schema.value), Some("""{"type":"object"}"""))

  test("autonomous turn disables the question tool"):
    val body = OpencodeArgs.message(AgentConfig.default, "hi", None, autonomous)
    assertEquals(body.tools.flatMap(_.get("question")), Some(false))

  test("interactive turn leaves the question tool enabled (no tools gate)"):
    val body =
      OpencodeArgs.message(AgentConfig.default, "hi", None, interactive)
    assertEquals(body.tools, None)

  test("read-only turn disables the write tools (write/edit/bash/patch)"):
    val cfg =
      AgentConfig.default.copy(
        tools = ToolSet.ReadOnly,
        autoApprove = AutoApprove.All
      )
    val tools =
      OpencodeArgs
        .message(cfg, "hi", None, interactive)
        .tools
        .getOrElse(Map.empty)
    assertEquals(tools.get("write"), Some(false))
    assertEquals(tools.get("edit"), Some(false))
    assertEquals(tools.get("bash"), Some(false))
    assertEquals(tools.get("patch"), Some(false))

  test("NetworkOnly keeps bash disabled (no writable-shell network)"):
    // opencode has no scoped network: NetworkOnly gates the same write tools as
    // ReadOnly, so the planner can't shell out to `gh`.
    val cfg = AgentConfig.default.copy(tools = ToolSet.NetworkOnly)
    val tools =
      OpencodeArgs
        .message(cfg, "hi", None, interactive)
        .tools
        .getOrElse(Map.empty)
    assertEquals(tools.get("bash"), Some(false))
    assertEquals(tools.get("edit"), Some(false))

  test("read-only autonomous turn gates both write tools and question"):
    val cfg = AgentConfig.default.copy(tools = ToolSet.ReadOnly)
    val tools =
      OpencodeArgs
        .message(cfg, "hi", None, autonomous)
        .tools
        .getOrElse(Map.empty)
    assertEquals(tools.get("write"), Some(false))
    assertEquals(tools.get("question"), Some(false))
