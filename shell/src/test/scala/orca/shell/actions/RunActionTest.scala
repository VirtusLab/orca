package orca.shell.actions

import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.run.{FallbackPolicy, FlowFlags, LaunchResult}

class RunActionTest extends munit.FunSuite:

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
      val recording = RecordingLaunch()

      val result = RunAction.run(
        flow,
        "add a rate limiter",
        RunAction.RunOptions(flags, FallbackPolicy.Refuse("hint")),
        os.pwd,
        terminal,
        recording.fn
      )

      assertEquals(result, LaunchResult.Ok)
      assertEquals(
        recording.calls.map(c => (c.fallback, c.flow, c.task, c.flags)),
        List(
          (
            FallbackPolicy.Refuse("hint"),
            flow.path,
            "add a rate limiter",
            flags
          )
        )
      )
