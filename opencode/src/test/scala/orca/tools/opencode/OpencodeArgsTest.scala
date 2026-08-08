package orca.tools.opencode

import orca.backend.{ConversationMode, SystemPromptComposer}
import orca.agents.{AutoApprove, AgentConfig, Model, ToolSet}

class OpencodeArgsTest extends munit.FunSuite:

  private val autonomous = ConversationMode.Autonomous
  private val interactive = ConversationMode.Interactive("display")

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
      AgentConfig()
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
      AgentConfig()
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
      OpencodeArgs.message(AgentConfig(), "hi", None, interactive).model,
      None
    )

  test("message carries the composed system prompt, config included"):
    // What opencode owns is that the composed prompt reaches `system`; which
    // rules compose is `SystemPromptComposerTest`'s. A non-default config, so
    // the assertion still bites if `message` hardcoded the default one.
    val config = AgentConfig(systemPrompt = Some("be terse"))
    val body = OpencodeArgs.message(config, "hi", None, interactive)
    assert(body.system.exists(_.startsWith("be terse")), body.system)
    assert(
      body.system.exists(
        _.endsWith(SystemPromptComposer.BackgroundWorkAbandonedAtTurnEnd)
      ),
      body.system
    )

  test("a read-only turn's system field carries the read-only rule"):
    val body = OpencodeArgs.message(
      AgentConfig().copy(tools = ToolSet.ReadOnly),
      "hi",
      None,
      interactive
    )
    assert(
      body.system.exists(_.contains(SystemPromptComposer.ReadOnlyTurn)),
      body.system
    )

  test("structured turn sets format=json_schema with the schema verbatim"):
    val body = OpencodeArgs.message(
      AgentConfig(),
      "hi",
      outputSchema = Some("""{"type":"object"}"""),
      interactive
    )
    assertEquals(body.format.map(_.`type`), Some("json_schema"))
    assertEquals(body.format.map(_.schema.value), Some("""{"type":"object"}"""))

  test("autonomous turn disables the question tool"):
    val body = OpencodeArgs.message(AgentConfig(), "hi", None, autonomous)
    assertEquals(body.tools.flatMap(_.get("question")), Some(false))

  test("interactive turn leaves the question tool enabled (no tools gate)"):
    val body =
      OpencodeArgs.message(AgentConfig(), "hi", None, interactive)
    assertEquals(body.tools, None)

  test("read-only turn disables the write tools, task and webfetch"):
    val cfg =
      AgentConfig().copy(
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
    assertEquals(tools.get("task"), Some(false))
    assertEquals(tools.get("webfetch"), Some(false))

  test("NetworkOnly keeps webfetch, with the write tools still disabled"):
    val cfg = AgentConfig().copy(tools = ToolSet.NetworkOnly)
    val tools =
      OpencodeArgs
        .message(cfg, "hi", None, interactive)
        .tools
        .getOrElse(Map.empty)
    assertEquals(tools.get("webfetch"), None)
    assertEquals(tools.get("bash"), Some(false))
    assertEquals(tools.get("edit"), Some(false))
    assertEquals(tools.get("task"), Some(false))

  test("read-only autonomous turn gates both write tools and question"):
    val cfg = AgentConfig().copy(tools = ToolSet.ReadOnly)
    val tools =
      OpencodeArgs
        .message(cfg, "hi", None, autonomous)
        .tools
        .getOrElse(Map.empty)
    assertEquals(tools.get("write"), Some(false))
    assertEquals(tools.get("question"), Some(false))
