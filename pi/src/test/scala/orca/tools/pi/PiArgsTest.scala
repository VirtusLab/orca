package orca.tools.pi

import orca.agents.{AgentConfig, Model, ToolSet}

class PiArgsTest extends munit.FunSuite:

  private val dir: os.Path = os.Path("/tmp/orca-pi-session")

  test("a fresh turn opens the session dir without --continue"):
    val args = PiArgs.rpc(dir, resume = false, AgentConfig.default, None)
    assertEquals(
      args.take(5),
      Seq("pi", "--mode", "rpc", "--session-dir", dir.toString)
    )
    assert(!args.contains("--continue"), args)

  test("a resumed turn adds --continue for the same session dir"):
    val args = PiArgs.rpc(dir, resume = true, AgentConfig.default, None)
    assert(args.containsSlice(Seq("--session-dir", dir.toString)), args)
    assert(args.contains("--continue"), args)

  test("model and system prompt file are rendered"):
    val args = PiArgs.rpc(
      dir,
      resume = false,
      AgentConfig.default.copy(model = Some(Model("openai/gpt-5"))),
      Some(os.Path("/tmp/system.md"))
    )
    assert(args.containsSlice(Seq("--model", "openai/gpt-5")), args)
    assert(
      args.containsSlice(Seq("--append-system-prompt", "/tmp/system.md")),
      args
    )

  test("read-only tools exclude writes"):
    val args = PiArgs.rpc(
      dir,
      resume = false,
      AgentConfig.default.copy(tools = ToolSet.ReadOnly),
      None
    )
    assert(args.containsSlice(Seq("--tools", "read,grep,find,ls")), args)

  test("NetworkOnly adds bash for network access"):
    val args = PiArgs.rpc(
      dir,
      resume = false,
      AgentConfig.default.copy(tools = ToolSet.NetworkOnly),
      None
    )
    assert(args.containsSlice(Seq("--tools", "read,grep,find,ls,bash")), args)

  test("interactive ask-user extension adds extension and ask_user tool"):
    val args = PiArgs.rpc(
      dir,
      resume = false,
      AgentConfig.default.copy(tools = ToolSet.ReadOnly),
      None,
      askUserExtension = Some(os.Path("/tmp/ask-user.ts"))
    )
    assert(
      args.containsSlice(Seq("--tools", "read,grep,find,ls,ask_user")),
      args
    )
    assert(args.containsSlice(Seq("--extension", "/tmp/ask-user.ts")), args)
