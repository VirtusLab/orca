package orca.backend.cursor

import orca.agents.{AutoApprove, AgentConfig, Model, WireSessionId, ToolSet}
import orca.agents.BackendTag

class CursorArgsTest extends munit.FunSuite:

  test("headless invokes cursor-agent with stream-json print mode"):
    val args = CursorArgs.headless("summarize", AgentConfig())
    assertEquals(
      args,
      Seq(
        "cursor-agent",
        "-p",
        "--output-format",
        "stream-json",
        "--force",
        "summarize"
      )
    )

  test("headless passes --model verbatim from AgentConfig"):
    val args = CursorArgs.headless(
      "x",
      AgentConfig().copy(model = Some(Model("composer-2.5")))
    )
    assert(args.containsSlice(Seq("--model", "composer-2.5")))

  test("resume adds --resume with the wire session id before the prompt"):
    val args = CursorArgs.resume(
      WireSessionId[BackendTag.Cursor.type]("sess-abc"),
      "continue",
      AgentConfig()
    )
    assert(args.containsSlice(Seq("--resume", "sess-abc")))
    assertEquals(args.last, "continue")

  test("AutoApprove.All maps to --force"):
    val args =
      CursorArgs.headless(
        "x",
        AgentConfig().copy(autoApprove = AutoApprove.All)
      )
    assert(args.contains("--force"))

  for tools <- List(ToolSet.ReadOnly, ToolSet.NetworkOnly, ToolSet.NoTools) do
    test(s"$tools retains plan without force on fresh and resumed calls"):
      val config = AgentConfig(tools = tools)
      val resumed = CursorArgs.resume(
        WireSessionId[BackendTag.Cursor.type](
          "6f0f1234-5678-4abc-9def-000000000001"
        ),
        "continue",
        config
      )
      for argv <- List(CursorArgs.headless("fresh", config), resumed) do
        assert(argv.contains("--plan"))
        assert(!argv.contains("--force"))
