package orca.shell.actions

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.shell.ShellVersion
import orca.shell.create.{CreateTarget, CreateTier}
import orca.shell.flows.{BuiltInFlows, DiscoveredFlow, FlowOrigin}
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher, LaunchResult}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.testkit.{GitRepo, TempDirs}

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

/** Records every [[FlowLauncher.FlowLaunch]]-shaped call it receives instead of
  * actually spawning `scala-cli`. `onLaunch` runs with the sandbox (the call's
  * `workDir`) mid-"run" — the seam where a test writes the authored file, or
  * snapshots sandbox state that [[AuthorAction]] deletes afterwards.
  */
private class RecordingLaunch(
    result: LaunchResult = LaunchResult.Ok,
    onLaunch: os.Path => Unit = _ => ()
):
  case class Call(
      fallback: FallbackPolicy,
      flow: os.Path,
      task: String,
      workDir: os.Path,
      flags: FlowFlags
  )
  var calls: List[Call] = Nil
  val fn: FlowLauncher.FlowLaunch =
    (fallback, flow, task, workDir, flags, _) =>
      calls = calls :+ Call(fallback, flow, task, workDir, flags)
      onLaunch(workDir)
      result

/** Runs `body` against a throwaway dumb JLine terminal, closed afterwards. */
private def withTerminal(body: Terminal => Unit): Unit =
  val terminal = TerminalBuilder.builder().dumb(true).build()
  try body(terminal)
  finally terminal.close()

class AuthorActionTest extends munit.FunSuite:

  private val builtInFlow =
    BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value) /
      "simple.sc"

  private def captured(body: => Unit): String =
    val buffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(buffer))(body)
    buffer.toString

  private def projectTarget(name: String): CreateTarget =
    val workDir = TempDirs.dir()
    CreateTarget(workDir / ".orca" / "flows" / name, workDir)

  /** Like [[projectTarget]], but rooted at an already-created `workDir` — used
    * by the commit tests, which need `workDir` to be a real git repo
    * ([[GitRepo]]) rather than a bare temp dir.
    */
  private def projectTargetIn(workDir: os.Path, name: String): CreateTarget =
    CreateTarget(workDir / ".orca" / "flows" / name, workDir)

  private def lastCommitMessage(repo: os.Path): String =
    os.proc("git", "log", "-1", "--pretty=%s").call(cwd = repo).out.text().trim

  private def commitCount(repo: os.Path): Int =
    os.proc("git", "rev-list", "--count", "HEAD")
      .call(cwd = repo)
      .out
      .text()
      .trim
      .toInt

  private def forkSource(workDir: os.Path): DiscoveredFlow =
    val sourcePath = workDir / ".orca" / "flows" / "implement.sc"
    os.write(sourcePath, "// desc\n", createFolders = true)
    DiscoveredFlow(
      name = "implement.sc",
      description = None,
      origin = FlowOrigin.Project,
      path = sourcePath,
      shadows = Nil
    )

  test(
    "create: launches the built-in simple.sc flow in a sandbox git repo, task targeting the sandbox"
  ):
    withTerminal: terminal =>
      val target = projectTarget("new.sc")
      // Snapshot sandbox state mid-launch: AuthorAction disposes of the
      // sandbox before returning, so nothing is observable afterwards.
      var sandboxState = Option.empty[(Boolean, Boolean, String)]
      val recording = RecordingLaunch(onLaunch =
        sandbox =>
          sandboxState = Some(
            (
              os.isDir(sandbox / ".git"),
              os.isDir(
                sandbox / ".orca" / "cache" / s"orca-api-${ShellVersion.value}"
              ),
              os.read(sandbox / ".orca" / "settings.properties")
            )
          )
      )

      val result = AuthorAction.create(
        "sync issues nightly",
        AuthorParams(CreateTier.Project, target),
        NoPromptUi,
        terminal,
        recording.fn
      )

      assertEquals(result, LaunchResult.Ok)
      assertEquals(recording.calls.size, 1)
      val call = recording.calls.head
      assertEquals(call.flow, builtInFlow)
      assertEquals(
        call.flags,
        FlowFlags(
          verbose = false,
          skipBranch = false,
          keepChanges = false,
          worktree = false
        )
      )
      assertEquals(call.fallback, FallbackPolicy.Ask(NoPromptUi))
      assert(call.task.contains("sync issues nightly"), call.task)
      // The prompt targets the sandbox-local file, never the real tier path.
      assert(call.task.contains((call.workDir / "new.sc").toString), call.task)
      assert(!call.task.contains(target.flowPath.toString), call.task)
      val (isGitRepo, hasApiMaterial, settings) = sandboxState.get
      assert(isGitRepo, "sandbox must be an initialized git repo")
      assert(hasApiMaterial, "API material must be extracted in the sandbox")
      assert(settings.contains("format = off"), settings)
      assert(settings.contains("lint = scala-cli compile 'new.sc'"), settings)
      assert(settings.contains("test = off"), settings)

  test(
    "fork: copies the source beside the API material and targets the sandbox-local fork file"
  ):
    withTerminal: terminal =>
      val target = projectTarget("implement-fork.sc")
      val source = forkSource(target.cwd)
      var taskSourcePathExisted = false
      val recording = RecordingLaunch(onLaunch = sandbox =>
        val copied =
          sandbox / ".orca" / "cache" / s"orca-api-${ShellVersion.value}" /
            "implement.sc"
        taskSourcePathExisted = os.isFile(copied)
      )

      val result = AuthorAction.fork(
        source,
        "add a retry step",
        AuthorParams(CreateTier.Project, target),
        NoPromptUi,
        terminal,
        recording.fn
      )

      assertEquals(result, LaunchResult.Ok)
      val call = recording.calls.head
      assertEquals(call.flow, builtInFlow)
      assert(call.task.contains("add a retry step"), call.task)
      assert(
        call.task.contains((call.workDir / "implement-fork.sc").toString),
        call.task
      )
      assert(
        taskSourcePathExisted,
        "the fork source must be copied into the sandbox's API-material dir"
      )

  test(
    "create: Ok with the authored file present copies it to the real target and deletes the sandbox"
  ):
    withTerminal: terminal =>
      val target = projectTarget("new.sc")
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "new.sc", "// a flow\n")
      )

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assertEquals(os.read(target.flowPath), "// a flow\n")
      assert(!os.exists(recording.calls.head.workDir), "sandbox must be gone")
      assert(output.contains(s"flow created at ${target.flowPath}"), output)

  test(
    "fork: overwrite=false with a pre-existing target refuses instead of overwriting it"
  ):
    // Target == source's own path (forkSource's fixed "implement.sc", pointed
    // at by a same-named target) — the shape edit-by-agent produces, minus
    // the overwrite flag.
    withTerminal: terminal =>
      val target = projectTarget("implement.sc")
      val source = forkSource(target.cwd)
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "implement.sc", "// edited\n")
      )

      val output = captured:
        val result = AuthorAction.fork(
          source,
          "add a retry step",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assertEquals(os.read(target.flowPath), "// desc\n")
      assert(os.isDir(recording.calls.head.workDir), "sandbox must be kept")
      assert(output.contains("appeared during the authoring run"), output)
      os.remove.all(recording.calls.head.workDir)

  test(
    "fork: overwrite=true copies over a pre-existing target (edit-by-agent)"
  ):
    withTerminal: terminal =>
      val target = projectTarget("implement.sc")
      val source = forkSource(target.cwd)
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "implement.sc", "// edited\n")
      )

      val output = captured:
        val result = AuthorAction.fork(
          source,
          "add a retry step",
          AuthorParams(CreateTier.Project, target, overwrite = true),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assertEquals(os.read(target.flowPath), "// edited\n")
      assert(!os.exists(recording.calls.head.workDir), "sandbox must be gone")
      assert(output.contains(s"flow updated at ${target.flowPath}"), output)

  test(
    "create: Project tier with a git repo commits the flow, leaving unrelated staged content staged"
  ):
    withTerminal: terminal =>
      val repo = GitRepo.seeded()
      val target = projectTargetIn(repo, "new.sc")
      os.write(repo / "unrelated.txt", "wip\n")
      val _ = os.proc("git", "add", "--", "unrelated.txt").call(cwd = repo)
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "new.sc", "// a flow\n")
      )

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assert(
        output.contains(s"flow created and committed at ${target.flowPath}"),
        output
      )
      assertEquals(lastCommitMessage(repo), "orca: add flow new.sc")
      val status =
        os.proc("git", "status", "--porcelain").call(cwd = repo).out.text()
      assert(
        status.linesIterator.exists(l =>
          l.trim.startsWith("A") && l.contains("unrelated.txt")
        ),
        s"the unrelated staged file must remain staged, not swept into the flow's commit:\n$status"
      )

  test("create: Global tier never attempts a commit"):
    withTerminal: terminal =>
      // A real repo, deliberately — proves the Global tier is skipped by its
      // own tier check, not merely because there happens to be no repo.
      val repo = GitRepo.seeded()
      val globalFlows = repo / "flows"
      val target = CreateTarget(globalFlows / "new.sc", globalFlows / os.up)
      val before = commitCount(repo)
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "new.sc", "// a flow\n")
      )

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Global, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assertEquals(commitCount(repo), before, "no new commit must appear")
      assert(output.contains(s"flow created at ${target.flowPath}"), output)
      assert(output.contains("commit it yourself"), output)

  test(
    "create: Project tier outside a git work tree skips the commit with a hint"
  ):
    withTerminal: terminal =>
      val target = projectTarget("new.sc") // plain TempDirs.dir(), not a repo
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "new.sc", "// a flow\n")
      )

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assert(output.contains(s"flow created at ${target.flowPath}"), output)
      assert(output.contains("commit it yourself"), output)

  test(
    "fork: overwrite=true commits with an 'update' message into the Project tier's repo"
  ):
    withTerminal: terminal =>
      val repo = GitRepo.seeded()
      val target = projectTargetIn(repo, "implement.sc")
      val source = forkSource(repo)
      val recording = RecordingLaunch(onLaunch =
        sandbox => os.write(sandbox / "implement.sc", "// edited\n")
      )

      val output = captured:
        val result = AuthorAction.fork(
          source,
          "add a retry step",
          AuthorParams(CreateTier.Project, target, overwrite = true),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assertEquals(os.read(target.flowPath), "// edited\n")
      assert(
        output.contains(s"flow updated and committed at ${target.flowPath}"),
        output
      )
      assertEquals(lastCommitMessage(repo), "orca: update flow implement.sc")

  test(
    "fork: overwrite picks editPrompt's wording, plain fork picks forkPrompt's"
  ):
    withTerminal: terminal =>
      val plainTarget = projectTarget("implement-fork.sc")
      val plainSource = forkSource(plainTarget.cwd)
      val plainRecording = RecordingLaunch()
      val _ = AuthorAction.fork(
        plainSource,
        "add a retry step",
        AuthorParams(CreateTier.Project, plainTarget),
        NoPromptUi,
        terminal,
        plainRecording.fn
      )
      val plainTask = plainRecording.calls.head.task
      assert(plainTask.contains("Create the Orca flow"), plainTask)
      assert(!plainTask.contains("Edit the Orca flow"), plainTask)

      val editTarget = projectTarget("implement.sc")
      val editSource = forkSource(editTarget.cwd)
      val editRecording = RecordingLaunch()
      val _ = AuthorAction.fork(
        editSource,
        "add a retry step",
        AuthorParams(CreateTier.Project, editTarget, overwrite = true),
        NoPromptUi,
        terminal,
        editRecording.fn
      )
      val editTask = editRecording.calls.head.task
      assert(editTask.contains("Edit the Orca flow"), editTask)
      assert(!editTask.contains("Create the Orca flow"), editTask)

  test(
    "create: Ok with the authored file absent warns clearly instead of silently succeeding"
  ):
    withTerminal: terminal =>
      val target = projectTarget("new.sc")
      val recording = RecordingLaunch()

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Ok)

      assert(!os.exists(target.flowPath))
      assert(!os.exists(recording.calls.head.workDir), "sandbox must be gone")
      assert(output.contains("new.sc was not written"), output)

  test("create: Failed keeps the sandbox for inspection and says where it is"):
    withTerminal: terminal =>
      val target = projectTarget("new.sc")
      val recording = RecordingLaunch(LaunchResult.Failed(1))

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Failed(1))

      val sandbox = recording.calls.head.workDir
      assert(os.isDir(sandbox), "sandbox must be kept after a failure")
      assert(output.contains(sandbox.toString), output)
      os.remove.all(sandbox)

  test("create: Cancelled deletes the sandbox and makes no claims"):
    withTerminal: terminal =>
      val target = projectTarget("new.sc")
      val recording = RecordingLaunch(LaunchResult.Cancelled)

      val output = captured:
        val result = AuthorAction.create(
          "sync issues nightly",
          AuthorParams(CreateTier.Project, target),
          NoPromptUi,
          terminal,
          recording.fn
        )
        assertEquals(result, LaunchResult.Cancelled)

      assert(!os.exists(recording.calls.head.workDir), "sandbox must be gone")
      assert(!output.contains("flow created"), output)
      assert(!output.contains("was not written"), output)
