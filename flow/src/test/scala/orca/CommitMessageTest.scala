package orca

import orca.events.OrcaEvent
import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId,
  ToolSet
}
import orca.progress.ProgressStore
import orca.tools.{GitTool, OsGitTool}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Tests for the llm-generated commit-message path (R13) in `recordAndCommit`.
  *
  * Strategy: build a `TestFlowControlWithLlm` that wires a real temp repo and a
  * stubbed LLM, then assert the message in `git log` after a stage runs.
  */
class CommitMessageTest extends munit.FunSuite:

  // --------------------------------------------------------------------------
  // Stubs
  // --------------------------------------------------------------------------

  /** LLM stub whose `autonomous.run` returns a fixed reply. Models both the
    * cheap (via `cheap`) and the full tool — the commit-message path calls
    * `fc.llm.cheap`, so `cheap` must also return this stub.
    */
  private def stubbedLlm(
      reply: String
  ): LlmTool[BackendTag.ClaudeCode.type] =
    new LlmTool[BackendTag.ClaudeCode.type]:
      val name: String = "stubbed"
      override def cheap: LlmTool[BackendTag.ClaudeCode.type] = this
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              prompt: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: LlmConfig,
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
            (session, reply)
      def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
      def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : LlmCall[BackendTag.ClaudeCode.type, O] = ???

  /** LLM stub that throws on `autonomous.run`. */
  private val throwingLlm: LlmTool[BackendTag.ClaudeCode.type] =
    new LlmTool[BackendTag.ClaudeCode.type]:
      val name: String = "throwing"
      override def cheap: LlmTool[BackendTag.ClaudeCode.type] = this
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
        new AutonomousTextCall[BackendTag.ClaudeCode.type]:
          def run(
              prompt: String,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: LlmConfig,
              emitPrompt: Boolean
          )(using
              orca.InStage
          ): (SessionId[BackendTag.ClaudeCode.type], String) =
            throw new RuntimeException("LLM unavailable")
      def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
      def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : LlmCall[BackendTag.ClaudeCode.type, O] = ???

  // --------------------------------------------------------------------------
  // Test helper
  // --------------------------------------------------------------------------

  /** A `FlowControl` backed by a real temp git repo and the given LLM stub. */
  private class FlowControlWithLlm(
      val llmStub: LlmTool[?],
      val git: GitTool,
      val progressStore: ProgressStore,
      val userPrompt: String = "p"
  ) extends FlowControl:
    import orca.llm.{ClaudeTool, CodexTool, GeminiTool, OpencodeTool, PiTool}
    private def stub(n: String) =
      throw new NotImplementedError(s"$n not wired")
    def llm: LlmTool[?] = llmStub
    lazy val claude: ClaudeTool = stub("claude")
    lazy val codex: CodexTool = stub("codex")
    lazy val opencode: OpencodeTool = stub("opencode")
    lazy val pi: PiTool = stub("pi")
    lazy val gemini: GeminiTool = stub("gemini")
    lazy val gh: orca.tools.GitHubTool = stub("gh")
    lazy val fs: orca.tools.FsTool = stub("fs")
    def emit(event: OrcaEvent): Unit = ()
    private val occ = new ConcurrentHashMap[String, AtomicInteger]
    def nextOccurrence(name: String): Int =
      occ.computeIfAbsent(name, _ => new AtomicInteger(0)).getAndIncrement()
    private val sessOcc = new AtomicInteger(0)
    def nextSessionOccurrence(): Int = sessOcc.getAndIncrement()

  private def withCtx(
      llmStub: LlmTool[?]
  )(body: (FlowControl, os.Path) => Unit): Unit =
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)
    val git = new OsGitTool(dir)
    val store = ProgressStore.default(dir, "p")
    given InStage = InStage.unsafe
    store.writeHeader(
      orca.progress.ProgressHeader("main", "feat/test", "deadbeef")
    )
    body(new FlowControlWithLlm(llmStub, git, store), dir)

  private def lastCommitMessage(dir: os.Path): String =
    os.proc("git", "log", "-1", "--pretty=%s").call(cwd = dir).out.text().trim

  // --------------------------------------------------------------------------
  // Tests
  // --------------------------------------------------------------------------

  test("stage with no commitMessage and non-empty diff uses llm.cheap message"):
    withCtx(stubbedLlm("Add feature file")): (ctx, dir) =>
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
    withCtx(stubbedLlm("should not appear")): (ctx, dir) =>
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
    withCtx(throwingLlm): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "stage: write file")

  test("stage with explicit commitMessage uses it verbatim (no llm call)"):
    // The explicit message path must not touch the LLM — use throwingLlm to
    // prove it.
    withCtx(throwingLlm): (ctx, dir) =>
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
    withCtx(stubbedLlm("   ")): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "stage: write file")

  test("stage with no commitMessage uses first line of multi-line llm reply"):
    withCtx(stubbedLlm("Add feature\n\nSome explanation here.")): (ctx, dir) =>
      given FlowControl = ctx
      val _ = stage("write file"):
        os.write.over(dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(dir), "Add feature")
