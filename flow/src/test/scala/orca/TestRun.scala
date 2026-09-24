package orca

import orca.agents.{Agent, BackendTag}
import orca.events.EventDispatcher
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.review.ReviewerCatalog
import orca.sessions.SessionStore
import orca.testkit.GitRepo
import orca.tools.OsGitTool

/** A test run's control and context, provided as givens by `import run.given`,
  * over the repo at `dir`.
  */
final case class TestRun(
    control: TestFlowControl,
    context: TestFlowContext,
    dir: os.Path
):
  given FlowControl = control
  given FlowContext = context

object TestRun:
  /** A run over a fresh temp git repo (with one seed commit so HEAD exists) and
    * a default progress store seeded with a header. Its context wires the
    * repo's git and `lead`.
    */
  def create(
      dispatcher: EventDispatcher,
      userPrompt: String = "p",
      lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
      stackSettings: StackSettings = StackSettings.empty,
      reviewerCatalog: ReviewerCatalog = ReviewerCatalog.builtIn,
      // False models a resume whose recorded base was dropped as unreachable.
      startingCommitUsable: Boolean = true
  ): TestRun =
    val dir = GitRepo.seeded()
    val git = new OsGitTool(dir)
    val runKey = RunKey.of(userPrompt)
    val store = ProgressStore.default(dir, runKey)
    given WorkspaceWrite = WorkspaceWrite.unsafe
    // The seed commit stands in for the commit a real run binds at.
    val headCommit = git.headCommit().get
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
    val context = new TestFlowContext(
      dispatcher,
      userPrompt,
      workDir = dir,
      stackSettings = stackSettings,
      reviewerCatalog = reviewerCatalog,
      lead = lead,
      wiredGit = Some(git)
    )
    TestRun(
      new TestFlowControl(
        store,
        SessionStore.default(dir, runKey),
        Option.when(startingCommitUsable)(headCommit)
      ),
      context,
      dir
    )
