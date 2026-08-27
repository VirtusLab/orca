package orca.shell.run

class FlowLauncherTest extends munit.FunSuite:

  private val flow = os.root / "home" / "u" / "flow.sc"
  private val workspaceDir = os.root / "home" / "u" / ".cache" / "workspace"

  /** Only the fields under test, defaulted off. Deliberately test-local:
    * production `FlowFlags` stays without defaults so every real construction
    * site keeps stating each flag.
    */
  private def flags(
      verbose: Boolean = false,
      skipBranch: Boolean = false,
      keepChanges: Boolean = false,
      worktree: Boolean = false
  ): FlowFlags = FlowFlags(verbose, skipBranch, keepChanges, worktree)

  test("argv forces --dep with a release version, before --workspace/--"):
    val result = FlowLauncher.argv(
      flow,
      Some("0.0.18"),
      "do the thing",
      flags(),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing"
      )
    )

  test("argv omits --dep when orcaVersion is None (dev build, pin-honouring)"):
    val result = FlowLauncher.argv(
      flow,
      None,
      "do the thing",
      flags(),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing"
      )
    )

  test(
    "argv adds --verbose (OrcaArgs's exact flag spelling) after -- when verbose is set"
  ):
    val result = FlowLauncher.argv(
      flow,
      Some("0.0.18"),
      "do the thing",
      flags(verbose = true),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing",
        "--verbose"
      )
    )
    assertEquals(
      result(result.indexOf("--") + 2),
      "--verbose",
      "the flow's own --verbose follows the task, after --"
    )

  test(
    "argv adds --skip-branch (OrcaArgs's exact flag spelling) after -- when set"
  ):
    val result = FlowLauncher.argv(
      flow,
      Some("0.0.18"),
      "do the thing",
      flags(skipBranch = true),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing",
        "--skip-branch"
      )
    )
    assert(
      result.indexOf("--skip-branch") > result.indexOf("--"),
      "--skip-branch must come after --"
    )

  test("argv adds both --verbose and --skip-branch, in that order, after --"):
    val result = FlowLauncher.argv(
      flow,
      None,
      "do the thing",
      flags(verbose = true, skipBranch = true),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing",
        "--verbose",
        "--skip-branch"
      )
    )

  test(
    "argv adds --keep-changes (OrcaArgs's exact flag spelling) after -- when set"
  ):
    val result = FlowLauncher.argv(
      flow,
      Some("0.0.18"),
      "do the thing",
      flags(keepChanges = true),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--dep",
        "org.virtuslab::orca:0.0.18",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing",
        "--keep-changes"
      )
    )
    assert(
      result.indexOf("--keep-changes") > result.indexOf("--"),
      "--keep-changes must come after --"
    )

  test(
    "argv adds --worktree (OrcaArgs's exact flag spelling) after -- when set"
  ):
    val result = FlowLauncher.argv(
      flow,
      None,
      "do the thing",
      flags(worktree = true),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing",
        "--worktree"
      )
    )

  test(
    "argv adds every flow flag in a fixed order after --: verbose, skip-branch, keep-changes, worktree"
  ):
    val result = FlowLauncher.argv(
      flow,
      None,
      "do the thing",
      flags(
        verbose = true,
        skipBranch = true,
        keepChanges = true,
        worktree = true
      ),
      workspaceDir
    )
    assertEquals(
      result,
      Seq(
        "scala-cli",
        "run",
        flow.toString,
        "--quiet",
        "--verbose",
        "--workspace",
        workspaceDir.toString,
        "--",
        "do the thing",
        "--verbose",
        "--skip-branch",
        "--keep-changes",
        "--worktree"
      )
    )

  test("argv keeps a spaces-bearing flow path as a single argv element"):
    val spacedFlow = os.root / "home" / "u" / "my flows" / "release.sc"
    val result = FlowLauncher.argv(
      spacedFlow,
      None,
      "task",
      flags(),
      workspaceDir
    )
    assertEquals(result(2), spacedFlow.toString)
    assertEquals(result.length, 9)

  test(
    "argv rejects a blank task — Main.promptTask should have re-prompted before this is ever called"
  ):
    intercept[IllegalArgumentException](
      FlowLauncher.argv(
        flow,
        None,
        "   ",
        flags(),
        workspaceDir
      )
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
