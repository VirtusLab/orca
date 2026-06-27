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
import orca.progress.ProgressStore
import orca.testkit.GitRepo
import orca.tools.{GitTool, OsGitTool}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Tests for the llm-generated commit-message path in `recordAndCommit`.
  *
  * Strategy: build a `TestFlowControlWithAgent` that wires a real temp repo and
  * a stubbed LLM, then assert the message in `git log` after a stage runs.
  */
class CommitMessageTest extends munit.FunSuite:

  // --------------------------------------------------------------------------
  // Stubs
  // --------------------------------------------------------------------------

  /** LLM stub whose `autonomous.run` returns a fixed reply. Models both the
    * cheap (via `cheap`) and the full tool — the commit-message path calls
    * `fc.llm.cheap`, so `cheap` must also return this stub.
    */
  private def stubbedAgent(
      reply: String
  ): Agent[BackendTag.ClaudeCode.type] =
    new Agent[BackendTag.ClaudeCode.type]:
      val name: String = "stubbed"
      override def cheap: Agent[BackendTag.ClaudeCode.type] = this
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              prompt: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: AgentConfig,
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
            (session, reply)
      def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
      def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] = ???

  /** LLM stub that throws on `autonomous.run`. */
  private val throwingAgent: Agent[BackendTag.ClaudeCode.type] =
    new Agent[BackendTag.ClaudeCode.type]:
      val name: String = "throwing"
      override def cheap: Agent[BackendTag.ClaudeCode.type] = this
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              prompt: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: AgentConfig,
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
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
      val llmStub: Agent[?],
      val git: GitTool,
      val progressStore: ProgressStore,
      val userPrompt: String = "p"
  ) extends FlowControl:
    import orca.agents.{
      ClaudeAgent,
      CodexAgent,
      GeminiAgent,
      OpencodeAgent,
      PiAgent
    }
    private def stub(n: String) =
      throw new NotImplementedError(s"$n not wired")
    def llm: Agent[?] = llmStub
    lazy val claude: ClaudeAgent = stub("claude")
    lazy val codex: CodexAgent = stub("codex")
    lazy val opencode: OpencodeAgent = stub("opencode")
    lazy val pi: PiAgent = stub("pi")
    lazy val gemini: GeminiAgent = stub("gemini")
    lazy val gh: orca.tools.GitHubTool = stub("gh")
    lazy val fs: orca.tools.FsTool = stub("fs")
    def emit(event: OrcaEvent): Unit = ()
    private val occ = new ConcurrentHashMap[String, AtomicInteger]
    def nextOccurrence(name: String): Int =
      occ.computeIfAbsent(name, _ => new AtomicInteger(0)).getAndIncrement()
    private val sessOcc = new AtomicInteger(0)
    def nextSessionOccurrence(): Int = sessOcc.getAndIncrement()

  private def withCtx(
      llmStub: Agent[?]
  )(body: (FlowControl, os.Path) => Unit): Unit =
    val dir = GitRepo.seeded()
    val git = new OsGitTool(dir)
    val store = ProgressStore.default(dir, "p")
    given InStage = InStage.unsafe
    store.writeHeader(
      orca.progress.ProgressHeader("main", "feat/test", "deadbeef")
    )
    body(new FlowControlWithAgent(llmStub, git, store), dir)

  private def lastCommitMessage(dir: os.Path): String =
    os.proc("git", "log", "-1", "--pretty=%s").call(cwd = dir).out.text().trim

  // --------------------------------------------------------------------------
  // Tests
  // --------------------------------------------------------------------------

  test("stage with no commitMessage and non-empty diff uses llm.cheap message"):
    withCtx(stubbedAgent("Add feature file")): (ctx, dir) =>
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
    withCtx(stubbedAgent("should not appear")): (ctx, dir) =>
      given FlowControl = ctx
      // Run a stage that produces no code changes — only the progress file changes.
      val _ = stage("no-op"):
        "done"
      // The commit message must be the fallback, not the LLM reply, because the
      // diff was empty (no code files modified in the body).
      assertEquals(lastCommitMessage(dir), "stage: no-op")

  test(
    "stage with no commitMessage and throwing llm falls back to stage:<name>"
  ):
    withCtx(throwingAgent): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "stage: write file")

  test("stage with explicit commitMessage uses it verbatim (no llm call)"):
    // The explicit message path must not touch the LLM — use throwingAgent to
    // prove it.
    withCtx(throwingAgent): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage[String](
        "write file",
        commitMessage = Some(_ => "explicit: my message")
      ):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "explicit: my message")

  test(
    "stage with no commitMessage and blank llm reply falls back to stage:<name>"
  ):
    withCtx(stubbedAgent("   ")): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "stage: write file")

  test("stage with no commitMessage uses first line of multi-line llm reply"):
    withCtx(stubbedAgent("Add feature\n\nSome explanation here.")):
      (ctx, dir) =>
        given FlowControl = ctx
        val _ = stage("write file"):
          os.write.over(dir / "seed.txt", "modified by stage")
          "done"
        assertEquals(lastCommitMessage(dir), "Add feature")
