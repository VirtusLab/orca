package orca.runner

import orca.ReportedFailure
import orca.{ConfigHome, FlowContext, OrcaDir, OrcaFlowException, StackSettings}
import orca.agents.{Agent, BackendTag, CodexAgent, GeminiAgent, Model}
import orca.events.OrcaListener
import orca.settings.SettingsFile
import orca.testkit.{GitRepo, TempDirs, currentBranch}
import orca.tools.OsGitTool

import java.util.concurrent.atomic.AtomicReference

/** End-to-end coverage of settings-driven role resolution through `runFlow`
  * (ADR 0020). The stub agent factories keep every backend off a real CLI.
  */
class RoleSettingsFlowTest extends munit.FunSuite:

  test("no settings anywhere: every role resolves to the wired claude"):
    val workDir = GitRepo.seeded()
    var roles: Option[(Agent[?], Agent[?], Agent[?])] = None
    val claude = StubAgent.claude
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      wiring = wiringWith(claude = claude)
    ):
      roles = Some(
        (
          summon[FlowContext].planningAgent,
          summon[FlowContext].codingAgent,
          summon[FlowContext].reviewAgent
        )
      )
    val (planning, coding, review) = roles.getOrElse(fail("body never ran"))
    assert(
      planning.sharesBackendWith(claude),
      "planning must be the wired claude"
    )
    assert(coding.sharesBackendWith(claude), "coding must be the wired claude")
    assert(review.sharesBackendWith(claude), "review must be the wired claude")

  test(
    "project file codingAgent = codex: coding is codex, planning stays claude"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val claude = StubAgent.claude
    val codex = StubAgent.codex
    var coding: Option[Agent[?]] = None
    var planning: Option[Agent[?]] = None
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      wiring = wiringWith(claude = claude, codex = codex)
    ):
      coding = Some(summon[FlowContext].codingAgent)
      planning = Some(summon[FlowContext].planningAgent)
    assert(
      coding.exists(_.sharesBackendWith(codex)),
      "coding must be the wired codex"
    )
    assert(
      planning.exists(_.sharesBackendWith(claude)),
      "an unset role stays the wired claude"
    )

  test("project reviewAgent wins over the user-global reviewAgent"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "reviewAgent = gemini\n")
    val globalHome = writeGlobal("reviewAgent = codex\n")
    val gemini = StubAgent.of(BackendTag.Gemini)
    val codex = StubAgent.codex
    var review: Option[Agent[?]] = None
    driveFlow(
      workDir,
      configHome = globalHome,
      stackSettings = Some(StackSettings.empty),
      wiring =
        wiringWith(claude = StubAgent.claude, codex = codex, gemini = gemini)
    ):
      review = Some(summon[FlowContext].reviewAgent)
    assert(
      review.exists(_.sharesBackendWith(gemini)),
      "the project file must win over global"
    )

  test("a programmatic codingAgent override beats a project file naming codex"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val gemini = StubAgent.of(BackendTag.Gemini)
    val codex = StubAgent.codex
    var coding: Option[Agent[?]] = None
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      codingOverride = Some(_.gemini),
      wiring =
        wiringWith(claude = StubAgent.claude, codex = codex, gemini = gemini)
    ):
      coding = Some(summon[FlowContext].codingAgent)
    assert(
      coding.exists(_.sharesBackendWith(gemini)),
      "the override must beat the project file"
    )

  test(
    "a malformed agent value in the project file aborts before any branch mutation"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = mistral\n")
    assertAbortsCleanly(workDir)

  test("a malformed user-global file aborts before any branch mutation"):
    val workDir = GitRepo.seeded()
    val globalHome = writeGlobal("codingAgent = mistral\n")
    assertAbortsCleanly(workDir, configHome = globalHome)

  test("a malformed project file aborts even under a stack override"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = mistral\n")
    assertAbortsCleanly(workDir, stackSettings = Some(StackSettings.empty))

  test("agent keys are honoured under a stack override"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val codex = StubAgent.codex
    val override_ = StackSettings(format = List("echo fmt"))
    var coding: Option[Agent[?]] = None
    var seenStack: Option[StackSettings] = None
    driveFlow(
      workDir,
      stackSettings = Some(override_),
      wiring = wiringWith(claude = StubAgent.claude, codex = codex)
    ):
      coding = Some(summon[FlowContext].codingAgent)
      seenStack = Some(summon[FlowContext].stackSettings)
    assert(
      coding.exists(_.sharesBackendWith(codex)),
      "codex must still be selected"
    )
    assertEquals(seenStack, Some(override_), "the stack override still governs")

  test(
    "an agents-only project file triggers discovery and appends the stack entries"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val canned = CannedDiscoveryAgent.on(BackendTag.Codex)(
      StackDiscoveryResult(
        format = DiscoveredTask(commands =
          List(DiscoveredCommand("echo fmt", "seed.txt"))
        ),
        lint = DiscoveredTask(),
        test = DiscoveredTask()
      )
    )
    driveFlow(
      workDir,
      wiring = wiringWith(claude = StubAgent.claude, codex = canned)
    ):
      // Real committed work keeps the feature branch (a throwaway branch is
      // deleted on teardown, taking the settings file with it), so the appended
      // file survives for inspection.
      val _ = orca.stage("work"):
        os.write(workDir / "work.txt", "real code")
        "done"
    val content = os.read(OrcaDir.settingsPath(workDir))
    val agentAt = content.indexOf("codingAgent = codex")
    val stackAt = content.indexOf("format = echo fmt")
    assert(
      agentAt >= 0,
      s"the user's agent line must survive discovery: $content"
    )
    assert(
      stackAt >= 0,
      s"the discovered stack entry must be appended: $content"
    )
    assert(
      agentAt < stackAt,
      s"the discovered stack entry must be APPENDED below the user's agent " +
        s"line, not prepended or interleaved: $content"
    )

  test(
    "a whitespace-only project file triggers discovery and writes the full file"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "   \n\t \n")
    val canned = CannedDiscoveryAgent(
      StackDiscoveryResult(
        format = DiscoveredTask(commands =
          List(DiscoveredCommand("echo fmt", "seed.txt"))
        ),
        lint = DiscoveredTask(),
        test = DiscoveredTask()
      )
    )
    driveFlow(
      workDir,
      wiring = wiringWith(claude = canned)
    ):
      // Real committed work keeps the feature branch (a throwaway branch is
      // deleted on teardown, taking the settings file with it), so the written
      // file survives for inspection.
      val _ = orca.stage("work"):
        os.write(workDir / "work.txt", "real code")
        "done"
    val content = os.read(OrcaDir.settingsPath(workDir))
    assert(
      content.startsWith(SettingsFile.Header),
      s"a blank existing file must get the full render, header included: $content"
    )

  test(
    "a discovery-written file with a live `off` line does not re-trigger discovery"
  ):
    val workDir = GitRepo.seeded()
    // A live `format = off` (discovery's own shape for an unset task)
    // configures the stack, so discovery must not run again — the plain codex
    // stub would throw if it did. A merely-commented example would not count —
    // this pins the live-line case specifically.
    writeProject(
      workDir,
      "codingAgent = codex\nformat = off\n"
    )
    val codex = StubAgent.codex
    var coding: Option[Agent[?]] = None
    driveFlow(
      workDir,
      wiring = wiringWith(claude = StubAgent.claude, codex = codex)
    ):
      coding = Some(summon[FlowContext].codingAgent)
    assert(
      coding.exists(_.sharesBackendWith(codex)),
      "coding is codex and discovery never ran"
    )

  test("the resolved roles are announced with their per-role sources"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val globalHome = writeGlobal("reviewAgent = gemini\n")
    val steps = new AtomicReference[List[String]](Nil)
    driveFlow(
      workDir,
      configHome = globalHome,
      stackSettings = Some(StackSettings.empty),
      listeners = List(recordSteps(steps)),
      wiring = wiringWith(
        claude = StubAgent.claude,
        codex = StubAgent.codex,
        gemini = StubAgent.of(BackendTag.Gemini)
      )
    )(())
    val announcements = steps.get().filter(_.startsWith("agents:"))
    assertEquals(
      announcements,
      List(
        "agents: planning=claude:<harness default> (default), " +
          "coding=codex:<harness default> (project), " +
          "review=gemini:<harness default> (global)"
      ),
      s"expected exactly one per-role announcement, saw: ${steps.get()}"
    )

  test("a project model pin is announced as harness:model"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex:gpt-5-mini\n")
    val steps = new AtomicReference[List[String]](Nil)
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      listeners = List(recordSteps(steps)),
      wiring = wiringWith(claude = StubAgent.claude, codex = StubAgent.codex)
    )(())
    val announcements = steps.get().filter(_.startsWith("agents:"))
    assertEquals(
      announcements,
      List(
        "agents: planning=claude:<harness default> (default), " +
          "coding=codex:gpt-5-mini (project), " +
          "review=claude:<harness default> (default)"
      ),
      s"expected the pinned model in the coding segment: ${steps.get()}"
    )

  test(
    "the wired claude's own configured default model is announced when unpinned"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val steps = new AtomicReference[List[String]](Nil)
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      listeners = List(recordSteps(steps)),
      wiring = wiringWith(
        claude = StubAgent.claude.withModel(Model("claude-opus-5-5[1m]")),
        codex = StubAgent.codex
      )
    )(())
    val announcements = steps.get().filter(_.startsWith("agents:"))
    assertEquals(
      announcements,
      List(
        "agents: planning=claude:claude-opus-5-5[1m] (default), " +
          "coding=codex:<harness default> (project), " +
          "review=claude:claude-opus-5-5[1m] (default)"
      ),
      s"expected the wired default model for the unpinned roles: ${steps.get()}"
    )

  test(
    "a symlinked project settings file aborts before any write or branch mutation"
  ):
    val workDir = GitRepo.seeded()
    // A committed `.orca/settings.properties` symlink pointing outside the tree.
    // The link is dangling (target absent), the shape whose `os.exists` reads
    // false and would otherwise drive a fresh-write discovery through the link.
    val outside = TempDirs.dir() / "outside.properties"
    val linkPath = OrcaDir.settingsPath(workDir)
    os.makeDir.all(linkPath / os.up)
    os.symlink(linkPath, outside)
    val startBranch = new OsGitTool(workDir).currentBranch()
    // A canned discovery agent that would succeed and write, so it's the abort,
    // not a discovery failure, that keeps the target file from being created.
    val canned = CannedDiscoveryAgent(
      StackDiscoveryResult(
        format = DiscoveredTask(commands =
          List(DiscoveredCommand("echo fmt", "seed.txt"))
        ),
        lint = DiscoveredTask(),
        test = DiscoveredTask()
      )
    )
    val _ = intercept[ReportedFailure]:
      driveFlow(workDir, wiring = wiringWith(claude = canned))(())
    assert(
      !os.exists(outside),
      "no discovery write must go through the symlink"
    )
    assertEquals(
      new OsGitTool(workDir).currentBranch(),
      startBranch,
      "the symlink abort must precede any branch mutation"
    )

  test(
    "a symlinked `.orca` directory aborts before any write or branch mutation"
  ):
    val workDir = GitRepo.seeded()
    // A committed `.orca` DIRECTORY symlink pointing at an existing directory
    // outside the tree. A leaf-only `os.isLink` check misses this, so without
    // the `.orca`-root guard every write (cache, lock, discovery output) would
    // land inside `outside`. `outside` starts empty, so any file appearing in it
    // is a write that went through the link.
    val outside = TempDirs.dir() / "outside-orca"
    os.makeDir.all(outside)
    os.symlink(OrcaDir.rootPath(workDir), outside)
    val startBranch = new OsGitTool(workDir).currentBranch()
    // A canned discovery agent that would succeed and write, so it's the abort,
    // not a discovery failure, that keeps the target from being written.
    val canned = CannedDiscoveryAgent(
      StackDiscoveryResult(
        format = DiscoveredTask(commands =
          List(DiscoveredCommand("echo fmt", "seed.txt"))
        ),
        lint = DiscoveredTask(),
        test = DiscoveredTask()
      )
    )
    val _ = intercept[OrcaFlowException]:
      driveFlow(workDir, wiring = wiringWith(claude = canned))(())
    assert(
      os.list(outside).isEmpty,
      "no write must go through the symlinked `.orca` directory"
    )
    assertEquals(
      new OsGitTool(workDir).currentBranch(),
      startBranch,
      "the symlinked-`.orca` abort must precede any branch mutation"
    )

  test(
    "a symlinked `.orca/cache` directory aborts before any write or branch mutation"
  ):
    val workDir = GitRepo.seeded()
    // A committed `.orca` real DIRECTORY holding a `.orca/cache` symlink pointing
    // off-tree. The `.orca`-root guard passes (it's a real dir), so a root-only
    // check would let `ensureCache`'s `os.makeDir.all` and the flow lock write
    // follow the link into `outside`; guarding every component from `.orca` down
    // catches the symlinked cache dir. `outside` starts empty, so any file
    // appearing in it is a write that escaped the tree.
    val outside = TempDirs.dir() / "outside-cache"
    os.makeDir.all(outside)
    os.makeDir.all(OrcaDir.rootPath(workDir))
    os.symlink(OrcaDir.rootPath(workDir) / "cache", outside)
    val startBranch = new OsGitTool(workDir).currentBranch()
    // A canned discovery agent that would succeed and write, so it's the abort,
    // not a discovery failure, that keeps the target from being written.
    val canned = CannedDiscoveryAgent(
      StackDiscoveryResult(
        format = DiscoveredTask(commands =
          List(DiscoveredCommand("echo fmt", "seed.txt"))
        ),
        lint = DiscoveredTask(),
        test = DiscoveredTask()
      )
    )
    val _ = intercept[OrcaFlowException]:
      driveFlow(workDir, wiring = wiringWith(claude = canned))(())
    assert(
      os.list(outside).isEmpty,
      "no write must go through the symlinked `.orca/cache` directory"
    )
    assertEquals(
      new OsGitTool(workDir).currentBranch(),
      startBranch,
      "the symlinked-`.orca/cache` abort must precede any branch mutation"
    )

  // --- fixtures -------------------------------------------------------------

  private def writeProject(workDir: os.Path, content: String): Unit =
    os.write(OrcaDir.settingsPath(workDir), content, createFolders = true)

  private def writeGlobal(content: String): ConfigHome =
    val home = ConfigHome(TempDirs.dir() / "orca")
    os.write(home.settings, content, createFolders = true)
    home

  private def absentGlobal(): ConfigHome = FlowHarness.absentConfigHome()

  private def recordSteps(sink: AtomicReference[List[String]]): OrcaListener =
    FlowHarness.recordSteps(sink)

  private def wiringWith(
      claude: orca.agents.ClaudeAgent,
      codex: CodexAgent = StubAgent.codex,
      gemini: GeminiAgent = StubAgent.of(BackendTag.Gemini)
  ): FlowWiring =
    FlowWiring(
      claude = Some(_ => claude),
      codex = Some(_ => codex),
      gemini = Some(_ => gemini)
    )

  /** Drives `runFlow` with a null-sink interaction and no progress store, so a
    * failure surfaces as a thrown `ReportedFailure` rather than a
    * `System.exit`. `configHome` defaults to an absent temp directory so no
    * test ever reads the developer's real `~/.config`.
    */
  private def driveFlow(
      workDir: os.Path,
      configHome: ConfigHome = absentGlobal(),
      stackSettings: Option[StackSettings] = None,
      planningOverride: Option[orca.AgentSet => Agent[?]] = None,
      codingOverride: Option[orca.AgentSet => Agent[?]] = None,
      reviewOverride: Option[orca.AgentSet => Agent[?]] = None,
      listeners: List[OrcaListener] = Nil,
      wiring: FlowWiring
  )(body: (orca.FlowContext, orca.FlowControl) ?=> Unit): Unit =
    FlowHarness.driveFlow(
      workDir = workDir,
      wiring = wiring,
      flowName = "role-settings",
      configHome = configHome,
      stackSettings = stackSettings,
      planningOverride = planningOverride,
      codingOverride = codingOverride,
      reviewOverride = reviewOverride,
      listeners = listeners
    )(body)

  /** A malformed settings file (project or global) must surface as a
    * `ReportedFailure` and leave HEAD on the starting branch — the abort
    * precedes `ensureClean` and any branch creation.
    */
  private def assertAbortsCleanly(
      workDir: os.Path,
      configHome: ConfigHome = absentGlobal(),
      stackSettings: Option[StackSettings] = None
  ): Unit =
    val startBranch = new OsGitTool(workDir).currentBranch()
    val _ = intercept[ReportedFailure]:
      driveFlow(
        workDir,
        configHome = configHome,
        stackSettings = stackSettings,
        wiring = wiringWith(claude = StubAgent.claude)
      )(())
    assertEquals(
      new OsGitTool(workDir).currentBranch(),
      startBranch,
      "the malformed-file abort must precede any branch mutation"
    )
