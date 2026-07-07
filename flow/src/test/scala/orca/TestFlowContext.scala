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
    val userPrompt: String = ""
) extends FlowContext,
      ReportedErrorsSupport:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowContext")

  type LeadB = BackendTag.ClaudeCode.type
  lazy val agent: Agent[LeadB] = stub("agent")
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
    val userPrompt: String = ""
) extends FlowControl,
      ReportedErrorsSupport,
      StageFrames:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowControl")

  type LeadB = BackendTag.ClaudeCode.type
  lazy val agent: Agent[LeadB] = stub("agent")
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
  // SAME implementation production uses, so this double cannot silently keep the
  // old flat-counter semantics and greenwash a nesting/resume test.

object TestFlowControl:
  /** Build a `TestFlowControl` over a fresh temp git repo (with one seed commit
    * so HEAD exists) and a default progress store seeded with a header. Returns
    * the control plus the repo dir for assertions on commits/files.
    */
  def create(
      dispatcher: EventDispatcher,
      userPrompt: String = "p"
  ): (TestFlowControl, os.Path) =
    val dir = GitRepo.seeded()
    val git = new OsGitTool(dir)
    val store = ProgressStore.default(dir, userPrompt)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    (new TestFlowControl(dispatcher, git, store, userPrompt), dir)
