package orca

import orca.events.{EventDispatcher, OrcaEvent}
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}
import orca.progress.{ProgressHeader, ProgressStore}
import orca.testkit.GitRepo
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.tools.OsGitTool

/** Single-threaded test-only backing for the reported-error set
  * ([[FlowContext.markErrorReported]] / [[FlowContext.errorAlreadyReported]]):
  * a plain var + identity (`eq`) scan mirrors the production reported-set
  * contract. The test contexts that mix this in are driven from a single
  * thread, so the bare var needs no synchronisation.
  */
private[orca] trait ReportedErrorsSupport:
  private var reportedErrors: List[Throwable] = Nil
  private[orca] def markErrorReported(e: Throwable): Unit =
    reportedErrors = e :: reportedErrors
  private[orca] def errorAlreadyReported(e: Throwable): Boolean =
    reportedErrors.exists(_ eq e)

/** Minimal FlowContext stub for unit-testing stage/fail and other helpers that
  * only touch `emit` + `userPrompt`. Tool accessors are lazy so merely
  * constructing the context doesn't throw; tests that exercise them should
  * provide real (or stubbed) implementations.
  */
class TestFlowContext(
    dispatcher: EventDispatcher,
    val userPrompt: String = "",
    val workDir: os.Path = orca.testkit.TempDirs.dir(),
    val stackSettings: StackSettings = StackSettings.empty
) extends FlowContext,
      ReportedErrorsSupport:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowContext")

  type PlanB = BackendTag.ClaudeCode.type
  type CodeB = BackendTag.ClaudeCode.type
  type ReviewB = BackendTag.ClaudeCode.type
  lazy val planningAgent: Agent[PlanB] = stub("planningAgent")
  lazy val codingAgent: Agent[CodeB] = stub("codingAgent")
  lazy val reviewAgent: Agent[ReviewB] = stub("reviewAgent")
  lazy val claude: ClaudeAgent = stub("claude")
  lazy val codex: CodexAgent = stub("codex")
  lazy val opencode: OpencodeAgent = stub("opencode")
  lazy val pi: PiAgent = stub("pi")
  lazy val gemini: GeminiAgent = stub("gemini")
  lazy val git: GitTool = stub("git")
  lazy val gh: GitHubTool = stub("gh")
  lazy val fs: FsTool = stub("fs")

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

/** A `FlowControl` backed by a real temp git repo and a real temp progress
  * store, for exercising the `stage` runtime (commit + resume). Stubs the LLM
  * tools (stages under test don't call them) but wires a real `OsGitTool` and a
  * default `ProgressStore` over freshly-`git init`ed temp dirs.
  */
class TestFlowControl(
    dispatcher: EventDispatcher,
    val git: GitTool,
    val progressStore: ProgressStore,
    val userPrompt: String = "",
    lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
    val workDir: os.Path = orca.testkit.TempDirs.dir(),
    val stackSettings: StackSettings = StackSettings.empty
) extends FlowControl,
      ReportedErrorsSupport,
      StageFrames:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowControl")

  // The three roles are backed by the same `lead` agent the test supplies —
  // the coding role drives commit messages, the review role the review
  // machinery, so a test that wires `lead` sees it through whichever role it
  // exercises.
  type PlanB = BackendTag.ClaudeCode.type
  type CodeB = BackendTag.ClaudeCode.type
  type ReviewB = BackendTag.ClaudeCode.type
  lazy val planningAgent: Agent[PlanB] = lead.getOrElse(stub("planningAgent"))
  lazy val codingAgent: Agent[CodeB] = lead.getOrElse(stub("codingAgent"))
  lazy val reviewAgent: Agent[ReviewB] = lead.getOrElse(stub("reviewAgent"))
  lazy val claude: ClaudeAgent = stub("claude")
  lazy val codex: CodexAgent = stub("codex")
  lazy val opencode: OpencodeAgent = stub("opencode")
  lazy val pi: PiAgent = stub("pi")
  lazy val gemini: GeminiAgent = stub("gemini")
  lazy val gh: GitHubTool = stub("gh")
  lazy val fs: FsTool = stub("fs")

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

  // Stage-identity bookkeeping (enterStage/exitStage/inStage and
  // nextSessionOccurrence) is inherited from the shared `StageFrames` mixin — the
  // SAME implementation production uses, so this double can't diverge from
  // production nesting/resume semantics and greenwash a test.

object TestFlowControl:
  /** Build a `TestFlowControl` over a fresh temp git repo (with one seed commit
    * so HEAD exists) and a default progress store seeded with a header. Returns
    * the control plus the repo dir for assertions on commits/files.
    */
  def create(
      dispatcher: EventDispatcher,
      userPrompt: String = "p",
      lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
      stackSettings: StackSettings = StackSettings.empty
  ): (TestFlowControl, os.Path) =
    val dir = GitRepo.seeded()
    val git = new OsGitTool(dir)
    val store = ProgressStore.default(dir, userPrompt)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    (
      new TestFlowControl(
        dispatcher,
        git,
        store,
        userPrompt,
        lead,
        dir,
        stackSettings
      ),
      dir
    )
