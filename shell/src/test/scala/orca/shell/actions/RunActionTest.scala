package orca.shell.actions

import orca.{OrcaArgs, RunTarget, Uncommitted}
import orca.discovery.Origin
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{FallbackPolicy, LaunchResult}

class RunActionTest extends munit.FunSuite:

  test("run hands the caller's args to the launcher unchanged"):
    withTerminal: terminal =>
      val flow = DiscoveredFlow(
        name = "implement.sc",
        description = None,
        origin = Origin.Project,
        path = os.root / "flows" / "implement.sc",
        shadows = Nil
      )
      // A non-default combination, so a launcher handed defaults of its own
      // instead of these fails here.
      val args =
        OrcaArgs(
          userPrompt = "add a rate limiter",
          verbose = true,
          target = RunTarget.NewBranch(Uncommitted.Keep),
          branch = None
        )
      val recording = RecordingLaunch()

      val result = RunAction.run(
        flow,
        RunAction.RunOptions(args, FallbackPolicy.Refuse("hint")),
        os.pwd,
        terminal,
        recording.fn
      )

      assertEquals(result, LaunchResult.Ok)
      assertEquals(
        recording.calls.map(c => (c.fallback, c.flow, c.args)),
        List((FallbackPolicy.Refuse("hint"), flow.path, args))
      )
