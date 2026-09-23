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
import orca.review.ReviewerCatalog
import orca.gitref.CommitHash
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.sessions.SessionStore
import orca.testkit.GitRepo
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.tools.OsGitTool

/** FlowContext stub for unit-testing stage/fail and other helpers that only
  * touch `emit` + `userPrompt`. Tool accessors are lazy so merely constructing
  * the context doesn't throw; `lead` backs the three roles, and `wiredGit` /
  * `wiredGh` the tools, for tests that exercise them.
  */
class TestFlowContext(
    dispatcher: EventDispatcher,
    val userPrompt: String = "",
    val workDir: os.Path = orca.testkit.TempDirs.dir(),
    val stackSettings: StackSettings = StackSettings.empty,
    val reviewerCatalog: ReviewerCatalog = ReviewerCatalog.builtIn,
    lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
    wiredGit: Option[GitTool] = None,
    wiredGh: Option[GitHubTool] = None
) extends FlowContext:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowContext")

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
  lazy val git: GitTool = wiredGit.getOrElse(stub("git"))
  lazy val gh: GitHubTool = wiredGh.getOrElse(stub("gh"))
  lazy val fs: FsTool = stub("fs")

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

/** A `FlowControl` backed by a real temp git repo and a real temp progress
  * store, for exercising the `stage` runtime (commit + resume). Its context is
  * a [[TestFlowContext]] wiring `git`, `gh` and `lead`; the other tools stay
  * stubbed.
  */
class TestFlowControl(
    dispatcher: EventDispatcher,
    git: GitTool,
    val progressStore: ProgressStore,
    val sessionStore: SessionStore,
    userPrompt: String = "",
    lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
    workDir: os.Path = orca.testkit.TempDirs.dir(),
    stackSettings: StackSettings = StackSettings.empty,
    private[orca] val startingCommit: Option[CommitHash] = None,
    reviewerCatalog: ReviewerCatalog = ReviewerCatalog.builtIn,
    gh: Option[GitHubTool] = None
) extends FlowControl,
      StageFrames:
  // The three roles are backed by the same `lead` agent the test supplies —
  // the coding role drives commit messages, the review role the review
  // machinery, so a test that wires `lead` sees it through whichever role it
  // exercises.
  val context: TestFlowContext = new TestFlowContext(
    dispatcher,
    userPrompt,
    workDir,
    stackSettings,
    reviewerCatalog,
    lead,
    Some(git),
    gh
  )

  // Stage-identity bookkeeping (withStage and claimSessionKey) and
  // the per-run turn claim are inherited from the shared `StageFrames` mixin —
  // the SAME implementation production uses, so this double can't diverge from
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
      stackSettings: StackSettings = StackSettings.empty,
      reviewerCatalog: ReviewerCatalog = ReviewerCatalog.builtIn,
      // False models a resume whose recorded base was dropped as unreachable.
      startingCommitUsable: Boolean = true
  ): (TestFlowControl, os.Path) =
    val dir = GitRepo.seeded()
    val git = new OsGitTool(dir)
    val runKey = RunKey.of(userPrompt)
    val store = ProgressStore.default(dir, runKey)
    val sessions = SessionStore.default(dir, runKey)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    // The seed commit stands in for the commit a real run binds at.
    val headCommit = git.headCommit().get
    val startingCommit = Option.when(startingCommitUsable)(headCommit)
    store.writeHeader(
      ProgressHeader(
        Some(orca.testkit.branchName("main")),
        orca.testkit.branchName("feat/test"),
        BranchMode.Created,
        userPrompt = userPrompt,
        flow = None,
        startingCommit = headCommit
      )
    )
    (
      new TestFlowControl(
        dispatcher,
        git,
        store,
        sessions,
        userPrompt,
        lead,
        dir,
        stackSettings,
        startingCommit,
        reviewerCatalog
      ),
      dir
    )
