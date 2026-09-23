package orca.shell.run

import orca.{FlowSourceProperty, OrcaArgs, RunTarget, Uncommitted}
import orca.progress.FlowSource
import orca.shell.OrcaBuild

class FlowLauncherTest extends munit.FunSuite:

  private val flowPath = os.root / "home" / "u" / "flow.sc"
  private val flow = LaunchedFlow(flowPath, FlowSource.Catalog("flow.sc"))
  private val sourceProp = FlowSourceProperty.assignment(flow.source)
  private val workspaceDir = os.root / "home" / "u" / ".cache" / "workspace"

  private val args = OrcaArgs(
    userPrompt = "do the thing",
    verbose = false,
    target = RunTarget.NewBranch(Uncommitted.Stash),
    branch = None
  )

  test("argv forces --dep with a release build, before --workspace/--"):
    val result = FlowLauncher.argv(
      flow,
      Some(OrcaBuild.Release("0.0.18")),
      args,
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flowPath.toString,
        "--quiet",
        "--verbose",
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--java-prop",
        sourceProp,
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing"
      )
    )

  test("argv forces a snapshot build with the local Ivy repository"):
    val result = FlowLauncher.argv(
      flow,
      Some(OrcaBuild.Snapshot("0.0.18+5-abc")),
      args,
      workspaceDir
    )
    assert(
      result.containsSlice(
        Seq(
          "--dep",
          "org.virtuslab::orca:0.0.18+5-abc",
          "--repository",
          "ivy2Local"
        )
      ),
      result
    )

  test("argv omits --dep for a pin-honouring run"):
    val result = FlowLauncher.argv(
      flow,
      None,
      args,
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flowPath.toString,
        "--quiet",
        "--verbose",
        "--java-prop",
        sourceProp,
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing"
      )
    )

  test("argv passes the flow's own args after --"):
    val flowArgs =
      args.copy(
        verbose = true,
        target = RunTarget.CurrentBranch(Uncommitted.Keep)
      )
    val result = FlowLauncher.argv(flow, None, flowArgs, workspaceDir)
    assertEquals(result.drop(result.indexOf("--") + 1), flowArgs.toArgv)

  test("argv keeps a spaces-bearing flow path as a single argv element"):
    val spacedFlow = os.root / "home" / "u" / "my flows" / "release.sc"
    val result = FlowLauncher.argv(
      flow.copy(path = spacedFlow),
      None,
      args,
      workspaceDir
    )
    assertEquals(result(2), spacedFlow.toString)
    assertEquals(result.length, 11)

  test(
    "argv rejects a blank task — the menu's task prompt should have re-prompted before this is ever called"
  ):
    intercept[IllegalArgumentException](
      FlowLauncher.argv(
        flow,
        None,
        args.copy(userPrompt = "   "),
        workspaceDir
      )
    )

  test("compileArgv passes the same flow source as argv"):
    // scala-cli rebuilds when a --java-prop value changes.
    val compile =
      FlowLauncher.compileArgv(flow, OrcaBuild.Release("0.0.18"), workspaceDir)
    assert(compile.containsSlice(Seq("--java-prop", sourceProp)), compile)

  test("resolveNextAction: a zero exit is Succeed, without the compile probe"):
    val probeCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val result =
      FlowLauncher.resolveNextAction(0, () => probeCalls.incrementAndGet())
    assertEquals(result, FlowLauncher.NextAction.Succeed)
    assertEquals(probeCalls.get(), 0)

  test(
    "resolveNextAction: a signal-range exit (SIGINT 130 / SIGTERM 143) is CancelledBySignal, without the compile probe"
  ):
    for signalExit <- List(130, 143) do
      val probeCalls = new java.util.concurrent.atomic.AtomicInteger(0)
      val result = FlowLauncher.resolveNextAction(
        signalExit,
        () => probeCalls.incrementAndGet()
      )
      assertEquals(result, FlowLauncher.NextAction.CancelledBySignal)
      assertEquals(probeCalls.get(), 0)

  test(
    "resolveNextAction: a failure with a clean compile probe is a genuine flow failure"
  ):
    assertEquals(
      FlowLauncher.resolveNextAction(1, () => 0),
      FlowLauncher.NextAction.ReportFailure(1)
    )

  test(
    "resolveNextAction: a failure with a failing compile probe offers the pin-honouring fallback"
  ):
    assertEquals(
      FlowLauncher.resolveNextAction(1, () => 1),
      FlowLauncher.NextAction.OfferFallback
    )

  test(
    "toLaunchResult: signal-range exits are Cancelled on any spawn path, others map to Ok/Failed"
  ):
    assertEquals(FlowLauncher.toLaunchResult(0), LaunchResult.Ok)
    assertEquals(FlowLauncher.toLaunchResult(1), LaunchResult.Failed(1))
    assertEquals(FlowLauncher.toLaunchResult(130), LaunchResult.Cancelled)
    assertEquals(FlowLauncher.toLaunchResult(143), LaunchResult.Cancelled)
