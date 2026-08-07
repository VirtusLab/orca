package orca

import orca.events.OrcaEvent
import orca.agents.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
  SessionId,
  ToolSet
}
import orca.progress.{ProgressStore, SessionRecord}
import orca.testkit.{GitRepo, TextReplyingAgent}
import orca.tools.{GitTool, OsGitTool}

import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for the agent-generated commit-message path in `recordAndCommit`: wire
  * a real temp repo and a stubbed LLM, then assert the message in `git log`
  * after a stage runs.
  */
class CommitMessageTest extends munit.FunSuite:

  // --------------------------------------------------------------------------
  // Stubs
  // --------------------------------------------------------------------------

  /** LLM stub that throws on `autonomous.run`. */
  private val throwingAgent: Agent[BackendTag.ClaudeCode.type] =
    new Agent[BackendTag.ClaudeCode.type]:
      val name: String = "throwing"
      override def cheap: Agent[BackendTag.ClaudeCode.type] = this
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          private[orca] def runWithSession(
              prompt: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              sessionName: Option[String],
              config: Option[AgentConfig],
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): String =
            throw new RuntimeException("LLM unavailable")
      def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
      def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] = ???

  // --------------------------------------------------------------------------
  // Test helper
  // --------------------------------------------------------------------------

  /** A `FlowControl` backed by a real temp git repo and the given LLM stub. */
  private class FlowControlWithAgent(
      val agentStub: Agent[BackendTag.ClaudeCode.type],
      val git: GitTool,
      val progressStore: ProgressStore,
      val workDir: os.Path,
      val userPrompt: String = "p",
      val stackSettings: StackSettings = StackSettings.empty
  ) extends FlowControl,
        ReportedErrorsSupport,
        StageFrames:
    import orca.agents.{
      ClaudeAgent,
      CodexAgent,
      GeminiAgent,
      OpencodeAgent,
      PiAgent
    }
    private def stub(n: String) =
      throw new NotImplementedError(s"$n not wired")
    // The coding role IS the test's stub; the commit path's
    // `fc.codingAgent.cheapOneShot` runs the stub's canned reply.
    type PlanB = BackendTag.ClaudeCode.type
    type CodeB = BackendTag.ClaudeCode.type
    type ReviewB = BackendTag.ClaudeCode.type
    def planningAgent: Agent[PlanB] = agentStub
    def codingAgent: Agent[CodeB] = agentStub
    def reviewAgent: Agent[ReviewB] = agentStub
    lazy val claude: ClaudeAgent = stub("claude")
    lazy val codex: CodexAgent = stub("codex")
    lazy val opencode: OpencodeAgent = stub("opencode")
    lazy val pi: PiAgent = stub("pi")
    lazy val gemini: GeminiAgent = stub("gemini")
    lazy val gh: orca.tools.GitHubTool = stub("gh")
    lazy val fs: orca.tools.FsTool = stub("fs")
    def emit(event: OrcaEvent): Unit = ()

  private def withCtx(
      agentStub: Agent[BackendTag.ClaudeCode.type]
  )(body: (FlowControl, os.Path) => Unit): Unit =
    val dir = GitRepo.seeded()
    val git = new OsGitTool(dir)
    val store = ProgressStore.default(dir, "p")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(
      orca.progress.ProgressHeader(
        "main",
        "feat/test",
        "deadbeef",
        orca.progress.BranchMode.Created
      )
    )
    body(new FlowControlWithAgent(agentStub, git, store, dir), dir)

  private def lastCommitMessage(dir: os.Path): String =
    os.proc("git", "log", "-1", "--pretty=%s").call(cwd = dir).out.text().trim

  /** The next prompt the stub was given, failing the test rather than returning
    * `null` when the commit path never reached the model.
    */
  private def nextPrompt(prompts: ConcurrentLinkedQueue[String]): String =
    Option(prompts.poll()).getOrElse(fail("no prompt reached the agent"))

  // --------------------------------------------------------------------------
  // Tests
  // --------------------------------------------------------------------------

  test(
    "stage with no commitMessage and non-empty diff uses agent.cheap message"
  ):
    withCtx(TextReplyingAgent("Add feature file")): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        // Modify the tracked seed file (not a new untracked file) so
        // `git diff HEAD` captures the change.
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "Add feature file")

  test("stage with no commitMessage but empty diff falls back to stage:<name>"):
    // An empty working-tree diff (no code changes, only the progress file
    // force-added) triggers the `s"stage: $name"` fallback.
    withCtx(TextReplyingAgent("should not appear")): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("no-op"):
        "done"
      assertEquals(lastCommitMessage(dir), "stage: no-op")

  test(
    "stage with no commitMessage and throwing agent falls back to stage:<name>"
  ):
    withCtx(throwingAgent): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "stage: write file")

  test("stage with explicit commitMessage uses it verbatim (no agent call)"):
    val prompts = ConcurrentLinkedQueue[String]()
    withCtx(TextReplyingAgent("should not appear", prompts)): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage[String](
        "write file",
        commitMessage = Some(_ => "explicit: my message")
      ):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "explicit: my message")
      assert(prompts.isEmpty, "the explicit-message path must not call a model")

  test("a large stage diff reaches the model bounded, with the --stat summary"):
    val prompts = ConcurrentLinkedQueue[String]()
    withCtx(TextReplyingAgent("Rewrite seed file", prompts)): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(
          dir / "seed.txt",
          (1 to 20000).map(i => s"line $i").mkString("\n")
        )
        "done"
      val prompt = nextPrompt(prompts)
      // The payload, not the whole prompt: its size is the contract, and the
      // instructions above it are free to change.
      val payload = prompt.drop(prompt.indexOf("Files changed:"))
      // Bounded, but not to a bare stat: the budget is spent on the diff head,
      // which reaches a few hundred lines in and stops well before the end.
      assert(clue(payload.length) <= BoundedDiff.CommitThreshold)
      assert(clue(payload.length) > BoundedDiff.CommitThreshold - 64)
      assert(prompt.contains("file changed"), "the --stat summary is missing")
      assert(prompt.contains("\n+line 300\n"), "the diff head is missing")
      assert(!prompt.contains("+line 5000"), "the diff was not truncated")
      assert(prompt.contains("…(truncated)"), "the cut went unmarked")

  test("a stage whose only change is a new file still describes it"):
    // An untracked file has no tracked history to diff against, but the stage's
    // `add -A` commit includes it — so the model has to see it too.
    val prompts = ConcurrentLinkedQueue[String]()
    withCtx(TextReplyingAgent("Add ignore rules", prompts)): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("add file"):
        os.write(dir / ".gitignore", "target/\n")
        "done"
      val prompt = nextPrompt(prompts)
      assert(prompt.contains("New files:\n.gitignore"), prompt)
      assert(prompt.contains("+target/"), "the new file's contents are missing")
      assertEquals(lastCommitMessage(dir), "Add ignore rules")

  test("a later stage's prompt excludes the .orca progress log"):
    val prompts = ConcurrentLinkedQueue[String]()
    withCtx(TextReplyingAgent("Update seed", prompts)): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("first"):
        os.write.over(dir / "seed.txt", "first change")
        "done"
      val _ = stage("second"):
        os.write.over(dir / "seed.txt", "second change")
        // What the runtime does mid-body once a session learns its wire id:
        // rewrite the progress log, which the first stage already committed —
        // so from here on it is a tracked file the stage diff would carry.
        ctx.progressStore.upsertSession(
          SessionRecord(
            name = "s",
            occurrence = 1,
            id = "sid",
            seed = "seed",
            resumeWireId = Some("wire"),
            backend = None
          )
        )
        "done"
      val _ = nextPrompt(prompts)
      val second = nextPrompt(prompts)
      assert(!second.contains(".orca"), second)
      assert(second.contains("seed.txt"), "the real change is missing")

  test(
    "stage with no commitMessage and blank agent reply falls back to stage:<name>"
  ):
    withCtx(TextReplyingAgent("   ")): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "stage: write file")

  test("stage with no commitMessage uses first line of multi-line agent reply"):
    withCtx(TextReplyingAgent("Add feature\n\nSome explanation here.")):
      (ctx, dir) =>
        given FlowControl = ctx
        val _ = stage("write file"):
          os.write.over(dir / "seed.txt", "modified by stage")
          "done"
        assertEquals(lastCommitMessage(dir), "Add feature")
