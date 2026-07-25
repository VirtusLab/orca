package orca.shell.actions

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.shell.ShellVersion
import orca.shell.create.{CreateTarget, CreateTier}
import orca.shell.flows.{BuiltInFlows, DiscoveredFlow, FlowOrigin}
import orca.shell.run.{FallbackPolicy, FlowFlags, LaunchResult}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.testkit.TempDirs

/** A [[ShellUi]] that fails loudly on any prompt — [[AuthorAction]] never
  * prompts directly, only threads `ui` through to [[FallbackPolicy.Ask]] for
  * the flow launcher to use if the forced version fails to compile.
  */
private object NoPromptUi extends ShellUi:
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    throw new UnsupportedOperationException("AuthorAction doesn't select")
  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException("AuthorAction doesn't confirm")
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("AuthorAction doesn't input")
  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("AuthorAction doesn't input")

/** Records every [[AuthorAction.FlowLaunch]]-shaped call it receives instead of
  * actually spawning `scala-cli` — stands in for [[FlowLauncher.runAnnounced]]
  * the way [[orca.shell.create.FlowAuthoring.suggestFilename]]'s `runner`
  * parameter stands in for a real subprocess.
  */
private class RecordingLaunch:
  case class Call(
      fallback: FallbackPolicy,
      flow: os.Path,
      task: String,
      workDir: os.Path,
      flags: FlowFlags
  )
  var calls: List[Call] = Nil
  val fn: AuthorAction.FlowLaunch =
    (fallback, flow, task, workDir, flags, _) =>
      calls = calls :+ Call(fallback, flow, task, workDir, flags)
      LaunchResult.Ok

class AuthorActionTest extends munit.FunSuite:

  private val builtInFlow =
    BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value) /
      "implement-interactive.sc"

  private def withTerminal(body: Terminal => Unit): Unit =
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try body(terminal)
    finally terminal.close()

  test(
    "create: launches the built-in implement-interactive.sc flow with the initial prompt as task, in workDir"
  ):
    withTerminal: terminal =>
      val workDir = TempDirs.dir()
      val target =
        CreateTarget(workDir / ".orca" / "flows" / "new.sc", workDir)
      val recording = RecordingLaunch()

      val result = AuthorAction.create(
        "sync issues nightly",
        AuthorParams(CreateTier.Project, target),
        workDir,
        NoPromptUi,
        terminal,
        recording.fn
      )

      assertEquals(result, LaunchResult.Ok)
      assertEquals(recording.calls.size, 1)
      val call = recording.calls.head
      assertEquals(call.flow, builtInFlow)
      assertEquals(call.workDir, workDir)
      assertEquals(call.flags, FlowFlags(verbose = false, skipBranch = false))
      assertEquals(call.fallback, FallbackPolicy.Ask(NoPromptUi))
      assert(call.task.contains("sync issues nightly"), call.task)
      assert(call.task.contains(target.flowPath.toString), call.task)

  test(
    "fork: launches the built-in flow with the fork prompt (source + changes) as task"
  ):
    withTerminal: terminal =>
      val workDir = TempDirs.dir()
      val sourcePath = workDir / ".orca" / "flows" / "implement.sc"
      os.write(sourcePath, "// desc\n", createFolders = true)
      val source = DiscoveredFlow(
        name = "implement.sc",
        description = None,
        origin = FlowOrigin.Project,
        path = sourcePath,
        shadows = Nil
      )
      val target =
        CreateTarget(
          workDir / ".orca" / "flows" / "implement-fork.sc",
          workDir
        )
      val recording = RecordingLaunch()

      val result = AuthorAction.fork(
        source,
        "add a retry step",
        AuthorParams(CreateTier.Project, target),
        workDir,
        NoPromptUi,
        terminal,
        recording.fn
      )

      assertEquals(result, LaunchResult.Ok)
      val call = recording.calls.head
      assertEquals(call.flow, builtInFlow)
      assertEquals(call.workDir, workDir)
      assert(call.task.contains("add a retry step"), call.task)
      assert(call.task.contains(sourcePath.toString), call.task)
      assert(call.task.contains(target.flowPath.toString), call.task)

  test("create: a global-tier target extracts API material under cwd/cache"):
    withTerminal: terminal =>
      val workDir = TempDirs.dir()
      val globalFlows = TempDirs.dir() / "flows"
      val configOrca = globalFlows / os.up
      val target = CreateTarget(globalFlows / "new.sc", configOrca)
      val recording = RecordingLaunch()

      val _ = AuthorAction.create(
        "sync issues nightly",
        AuthorParams(CreateTier.Global, target),
        workDir,
        NoPromptUi,
        terminal,
        recording.fn
      )

      assert(
        os.isDir(configOrca / "cache" / s"orca-api-${ShellVersion.value}"),
        "expected the API material extracted under the global tier's cwd/cache"
      )
      // The flow itself still launches from workDir (the actual repo), not
      // the global tier's config-home directory.
      assertEquals(recording.calls.head.workDir, workDir)
