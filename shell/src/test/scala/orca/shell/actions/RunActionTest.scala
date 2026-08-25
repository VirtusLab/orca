package orca.shell.actions

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher, LaunchResult}

class RunActionTest extends munit.FunSuite:

  private def withTerminal(body: Terminal => Unit): Unit =
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try body(terminal)
    finally terminal.close()

  test("run hands the caller's flags to the launcher unchanged"):
    withTerminal: terminal =>
      val flow = DiscoveredFlow(
        name = "implement.sc",
        description = None,
        origin = FlowOrigin.Project,
        path = os.root / "flows" / "implement.sc",
        shadows = Nil
      )
      // A non-default combination, so a launcher handed defaults of its own
      // instead of these fails here.
      val flags =
        FlowFlags(verbose = true, skipBranch = false, keepChanges = true)
      var seen = Option.empty[(FallbackPolicy, os.Path, String, FlowFlags)]
      val launch: FlowLauncher.FlowLaunch =
        (fallback, launched, task, _, launchedFlags, _) =>
          seen = Some((fallback, launched, task, launchedFlags))
          LaunchResult.Ok

      val result = RunAction.run(
        flow,
        "add a rate limiter",
        RunAction.RunOptions(flags, FallbackPolicy.Refuse("hint")),
        os.pwd,
        terminal,
        launch
      )

      assertEquals(result, LaunchResult.Ok)
      assertEquals(
        seen,
        Some(
          (
            FallbackPolicy.Refuse("hint"),
            flow.path,
            "add a rate limiter",
            flags
          )
        )
      )
