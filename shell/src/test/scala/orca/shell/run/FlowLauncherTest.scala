package orca.shell.run

class FlowLauncherTest extends munit.FunSuite:

  private val flow = os.root / "home" / "u" / "flow.sc"

  test("argv forces --dep with a release version, before --"):
    val result =
      FlowLauncher.argv(flow, Some("0.0.18"), "do the thing", verbose = false)
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--",
        "do the thing"
      )
    )

  test("argv omits --dep when orcaVersion is None (dev build, pin-honouring)"):
    val result = FlowLauncher.argv(flow, None, "do the thing", verbose = false)
    assertEquals(
      result,
      Seq("scala-cli", "run", flow.toString, "--", "do the thing")
    )

  test(
    "argv adds --verbose (OrcaArgs's exact flag spelling) after -- when verbose is set"
  ):
    val result =
      FlowLauncher.argv(flow, Some("0.0.18"), "do the thing", verbose = true)
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--",
        "do the thing",
        "--verbose"
      )
    )
    assert(
      result.indexOf("--verbose") > result.indexOf("--"),
      "--verbose must come after --"
    )

  test("argv keeps a spaces-bearing flow path as a single argv element"):
    val spacedFlow = os.root / "home" / "u" / "my flows" / "release.sc"
    val result = FlowLauncher.argv(spacedFlow, None, "task", verbose = false)
    assertEquals(result(2), spacedFlow.toString)
    assertEquals(result.length, 5)

  test(
    "argv rejects a blank task — Main.promptTask should have re-prompted before this is ever called"
  ):
    intercept[IllegalArgumentException](
      FlowLauncher.argv(flow, None, "   ", verbose = false)
    )

  test("childEnv sets ORCA_FLOW_NAME to the flow script's filename"):
    assertEquals(
      FlowLauncher.childEnv(flow),
      Map("ORCA_FLOW_NAME" -> "flow.sc")
    )

  test(
    "resolveNextAction: a signal-range exit (SIGINT 130 / SIGTERM 143) is CancelledBySignal, without invoking the compile probe"
  ):
    for signalExit <- List(130, 143) do
      val probeCalls = new java.util.concurrent.atomic.AtomicInteger(0)
      val result = FlowLauncher.resolveNextAction(
        signalExit,
        forcedVersionDefined = true,
        () => probeCalls.incrementAndGet()
      )
      assertEquals(result, FlowLauncher.NextAction.CancelledBySignal)
      assertEquals(probeCalls.get(), 0)

  test(
    "resolveNextAction: a non-signal failure (1) still invokes the compile probe"
  ):
    val probeCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val result = FlowLauncher.resolveNextAction(
      1,
      forcedVersionDefined = true,
      () => { probeCalls.incrementAndGet(); 0 }
    )
    assertEquals(result, FlowLauncher.NextAction.ReportFailure(1))
    assertEquals(probeCalls.get(), 1)

  test(
    "decideNextAction: forced run succeeding is Succeed regardless of any compile probe"
  ):
    assertEquals(
      FlowLauncher.decideNextAction(0, None),
      FlowLauncher.NextAction.Succeed
    )
    assertEquals(
      FlowLauncher.decideNextAction(0, Some(1)),
      FlowLauncher.NextAction.Succeed
    )

  test(
    "decideNextAction: forced failure with a clean compile probe is a genuine flow failure"
  ):
    assertEquals(
      FlowLauncher.decideNextAction(1, Some(0)),
      FlowLauncher.NextAction.ReportFailure(1)
    )

  test(
    "decideNextAction: forced failure with a failing compile probe offers the pin-honouring fallback"
  ):
    assertEquals(
      FlowLauncher.decideNextAction(1, Some(1)),
      FlowLauncher.NextAction.OfferFallback
    )

  test(
    "decideNextAction: forced failure with no compile probe (already pin-honouring) reports the failure directly"
  ):
    assertEquals(
      FlowLauncher.decideNextAction(1, None),
      FlowLauncher.NextAction.ReportFailure(1)
    )

  test(
    "toLaunchResult: signal-range exits are Cancelled on any spawn path, others map to Ok/Failed"
  ):
    assertEquals(FlowLauncher.toLaunchResult(0), LaunchResult.Ok)
    assertEquals(FlowLauncher.toLaunchResult(1), LaunchResult.Failed(1))
    assertEquals(FlowLauncher.toLaunchResult(130), LaunchResult.Cancelled)
    assertEquals(FlowLauncher.toLaunchResult(143), LaunchResult.Cancelled)
