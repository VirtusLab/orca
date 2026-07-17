package orca.runner

import orca.{FlowContext, OrcaArgs, OrcaDir, StackSettings, runFlow}
import orca.agents.{
  Agent,
  AgentCall,
  AgentConfig,
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  CodexAgent,
  GeminiAgent,
  InteractiveAgentCall,
  JsonData,
  Model,
  SessionId,
  ToolSet
}
import orca.events.{OrcaEvent, OrcaListener}
import orca.settings.SettingsFile
import orca.testkit.{GitRepo, TempDirs}
import orca.tools.OsGitTool
import orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

/** End-to-end coverage of settings-driven role resolution through `runFlow`
  * (ADR 0020): the three role agents resolve from the project and user-global
  * settings files (and the per-role programmatic overrides), a malformed file
  * aborts before any tree mutation, agent keys survive a stack override, and an
  * agents-only file still triggers stack discovery (appended, not clobbered).
  * The stub agent factories keep every backend off a real CLI.
  */
class RoleSettingsFlowTest extends munit.FunSuite:

  test("no settings anywhere: every role resolves to the wired claude"):
    val workDir = GitRepo.seeded()
    var roles: Option[(Agent[?], Agent[?], Agent[?])] = None
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      wiring = wiringWith(claude = StubAgent.claude)
    ):
      roles = Some(
        (
          summon[FlowContext].planningAgent,
          summon[FlowContext].codingAgent,
          summon[FlowContext].reviewAgent
        )
      )
    val (planning, coding, review) = roles.getOrElse(fail("body never ran"))
    assert(planning.eq(StubAgent.claude), "planning must be the wired claude")
    assert(coding.eq(StubAgent.claude), "coding must be the wired claude")
    assert(review.eq(StubAgent.claude), "review must be the wired claude")

  test(
    "project file codingAgent = codex: coding is codex, planning stays claude"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val codex = new StubCodex
    var coding: Option[Agent[?]] = None
    var planning: Option[Agent[?]] = None
    driveFlow(
      workDir,
      stackSettings = Some(StackSettings.empty),
      wiring = wiringWith(claude = StubAgent.claude, codex = codex)
    ):
      coding = Some(summon[FlowContext].codingAgent)
      planning = Some(summon[FlowContext].planningAgent)
    assert(coding.exists(_.eq(codex)), "coding must be the wired codex")
    assert(
      planning.exists(_.eq(StubAgent.claude)),
      "an unset role stays the wired claude"
    )

  test("project reviewAgent wins over the user-global reviewAgent"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "reviewAgent = gemini\n")
    val globalPath = writeGlobal("reviewAgent = codex\n")
    val gemini = new StubGemini
    val codex = new StubCodex
    var review: Option[Agent[?]] = None
    driveFlow(
      workDir,
      globalSettingsPath = globalPath,
      stackSettings = Some(StackSettings.empty),
      wiring =
        wiringWith(claude = StubAgent.claude, codex = codex, gemini = gemini)
    ):
      review = Some(summon[FlowContext].reviewAgent)
    assert(review.exists(_.eq(gemini)), "the project file must win over global")

  test("a programmatic codingAgent override beats a project file naming codex"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val gemini = new StubGemini
    val codex = new StubCodex
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
      coding.exists(_.eq(gemini)),
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
    val globalPath = writeGlobal("codingAgent = mistral\n")
    assertAbortsCleanly(workDir, globalSettingsPath = globalPath)

  test("a malformed project file aborts even under a stack override"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = mistral\n")
    assertAbortsCleanly(workDir, stackSettings = Some(StackSettings.empty))

  test("agent keys are honoured under a stack override"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val codex = new StubCodex
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
    assert(coding.exists(_.eq(codex)), "codex must still be selected")
    assertEquals(seenStack, Some(override_), "the stack override still governs")

  test(
    "an agents-only project file triggers discovery and appends the stack entries"
  ):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val canned = new CannedDiscoveryCodex(
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
      // deleted on teardown, taking the just-committed settings file with it),
      // so the appended file is still present to inspect.
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
      // deleted on teardown, taking the just-written settings file with it),
      // so the written file is still present to inspect.
      val _ = orca.stage("work"):
        os.write(workDir / "work.txt", "real code")
        "done"
    val content = os.read(OrcaDir.settingsPath(workDir))
    assert(
      content.startsWith(SettingsFile.Header),
      s"a blank existing file must get the full render, header included: $content"
    )

  test(
    "a discovery-written file with only commented stack lines does not re-trigger discovery"
  ):
    val workDir = GitRepo.seeded()
    // The stack lines are all commented (as a discovery-written file leaves
    // them), so `hasStackLines` is true and discovery must NOT run again — the
    // plain codex stub would throw if it did.
    writeProject(
      workDir,
      "codingAgent = codex\n# format =   (no formatter found)\n"
    )
    val codex = new StubCodex
    var coding: Option[Agent[?]] = None
    driveFlow(
      workDir,
      wiring = wiringWith(claude = StubAgent.claude, codex = codex)
    ):
      coding = Some(summon[FlowContext].codingAgent)
    assert(
      coding.exists(_.eq(codex)),
      "coding is codex and discovery never ran"
    )

  test("the resolved roles are announced with their per-role sources"):
    val workDir = GitRepo.seeded()
    writeProject(workDir, "codingAgent = codex\n")
    val globalPath = writeGlobal("reviewAgent = gemini\n")
    val steps = new AtomicReference[List[String]](Nil)
    driveFlow(
      workDir,
      globalSettingsPath = globalPath,
      stackSettings = Some(StackSettings.empty),
      listeners = List(recordSteps(steps)),
      wiring = wiringWith(
        claude = StubAgent.claude,
        codex = new StubCodex,
        gemini = new StubGemini
      )
    )(())
    val announcements = steps.get().filter(_.startsWith("agents:"))
    assertEquals(
      announcements,
      List(
        "agents: planning=claude (default), coding=codex (project), " +
          "review=gemini (global)"
      ),
      s"expected exactly one per-role announcement, saw: ${steps.get()}"
    )

  test(
    "a symlinked project settings file aborts before any write or branch mutation"
  ):
    val workDir = GitRepo.seeded()
    // A committed `.orca/settings.properties` symlink pointing outside the tree:
    // `os.write.over` follows the link, so discovery would write its output at
    // `outside`. The link is dangling (target absent), which is exactly the
    // shape whose `os.exists` reads false and would otherwise drive a
    // fresh-write discovery straight through the link.
    val outside = TempDirs.dir() / "outside.properties"
    val linkPath = OrcaDir.settingsPath(workDir)
    os.makeDir.all(linkPath / os.up)
    os.symlink(linkPath, outside)
    val startBranch = new OsGitTool(workDir).currentBranch()
    // A canned discovery agent that WOULD succeed and write, so the abort — not
    // a discovery failure — is what keeps the target file from being created.
    val canned = CannedDiscoveryAgent(
      StackDiscoveryResult(
        format = DiscoveredTask(commands =
          List(DiscoveredCommand("echo fmt", "seed.txt"))
        ),
        lint = DiscoveredTask(),
        test = DiscoveredTask()
      )
    )
    val _ = intercept[SurfacedFlowFailure]:
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

  // --- fixtures -------------------------------------------------------------

  private def writeProject(workDir: os.Path, content: String): Unit =
    os.write(OrcaDir.settingsPath(workDir), content, createFolders = true)

  private def writeGlobal(content: String): os.Path =
    val path = TempDirs.dir() / "orca" / "settings.properties"
    os.write(path, content, createFolders = true)
    path

  private def absentGlobal(): os.Path =
    TempDirs.dir() / "orca" / "settings.properties"

  private def recordSteps(sink: AtomicReference[List[String]]): OrcaListener =
    case OrcaEvent.Step(msg) => val _ = sink.updateAndGet(_ :+ msg)
    case _                   => ()

  private def wiringWith(
      claude: orca.agents.ClaudeAgent,
      codex: CodexAgent = new StubCodex,
      gemini: GeminiAgent = new StubGemini
  ): FlowWiring =
    FlowWiring(
      claude = Some(_ => claude),
      codex = Some(_ => codex),
      gemini = Some(_ => gemini)
    )

  /** Drives `runFlow` with a null-sink interaction and no progress store, so a
    * failure surfaces as a thrown `SurfacedFlowFailure` rather than a
    * `System.exit`. `globalSettingsPath` defaults to an absent temp path so no
    * test ever reads the developer's real `~/.config`.
    */
  private def driveFlow(
      workDir: os.Path,
      globalSettingsPath: os.Path = absentGlobal(),
      stackSettings: Option[StackSettings] = None,
      planningOverride: Option[orca.AgentSet => Agent[?]] = None,
      codingOverride: Option[orca.AgentSet => Agent[?]] = None,
      reviewOverride: Option[orca.AgentSet => Agent[?]] = None,
      listeners: List[OrcaListener] = Nil,
      wiring: FlowWiring
  )(body: orca.FlowControl ?=> Unit): Unit =
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs("role-settings"),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = listeners,
        branchNaming = None,
        stackSettings = stackSettings,
        planningAgent = planningOverride,
        codingAgent = codingOverride,
        reviewAgent = reviewOverride,
        returnToStartBranch = false,
        progressStore = None,
        globalSettingsPath = globalSettingsPath,
        wiring = wiring
      )(body)

  /** A malformed settings file (project or global) must surface as a
    * `SurfacedFlowFailure` and leave HEAD on the starting branch — the abort
    * precedes `ensureClean` and any branch creation.
    */
  private def assertAbortsCleanly(
      workDir: os.Path,
      globalSettingsPath: os.Path = absentGlobal(),
      stackSettings: Option[StackSettings] = None
  ): Unit =
    val startBranch = new OsGitTool(workDir).currentBranch()
    val _ = intercept[SurfacedFlowFailure]:
      driveFlow(
        workDir,
        globalSettingsPath = globalSettingsPath,
        stackSettings = stackSettings,
        wiring = wiringWith(claude = StubAgent.claude)
      )(())
    assertEquals(
      new OsGitTool(workDir).currentBranch(),
      startBranch,
      "the malformed-file abort must precede any branch mutation"
    )

  /** A `CodexAgent` stub: every builder returns `this`, every call throws — the
    * codex sibling of [[StubClaudeAgent]], for a role that must never reach a
    * real CLI.
    */
  private class StubCodex extends CodexAgent:
    val name = "stub-codex"
    def mini: CodexAgent = this
    def withModel(model: Model): CodexAgent = this
    def withConfig(config: AgentConfig): CodexAgent = this
    def withSystemPrompt(prompt: String): CodexAgent = this
    def withName(name: String): CodexAgent = this
    def withTools(tools: ToolSet): CodexAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Codex.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
      throw new UnsupportedOperationException

  /** A `GeminiAgent` stub, sibling of [[StubCodex]]. */
  private class StubGemini extends GeminiAgent:
    val name = "stub-gemini"
    def flash: GeminiAgent = this
    def withModel(model: Model): GeminiAgent = this
    def withConfig(config: AgentConfig): GeminiAgent = this
    def withSystemPrompt(prompt: String): GeminiAgent = this
    def withName(name: String): GeminiAgent = this
    def withTools(tools: ToolSet): GeminiAgent = this
    def autonomous: AutonomousTextCall[BackendTag.Gemini.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Gemini.type, O] =
      throw new UnsupportedOperationException

  /** The codex discovery seam, mirroring [[CannedDiscoveryAgent]] for the codex
    * role: `resultAs[O].autonomous.run` returns the canned discovery result in
    * the [[StackDiscoveryReply]] envelope; free-text calls throw (branch naming
    * falls back to the deterministic slug).
    */
  private class CannedDiscoveryCodex(result: StackDiscoveryResult)
      extends StubCodex:
    override def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Codex.type, O] =
      new AgentCall[BackendTag.Codex.type, O]:
        val autonomous: AutonomousAgentCall[BackendTag.Codex.type, O] =
          new AutonomousAgentCall[BackendTag.Codex.type, O]:
            private[orca] def runWithSession[I: AgentInput](
                input: I,
                session: SessionId[BackendTag.Codex.type],
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using orca.InStage): O =
              StackDiscoveryReply(result).asInstanceOf[O]
        def interactive: InteractiveAgentCall[BackendTag.Codex.type, O] =
          throw new UnsupportedOperationException
