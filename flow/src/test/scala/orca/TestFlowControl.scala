package orca

import orca.agents.{Agent, BackendTag}
import orca.events.EventDispatcher
import orca.gitref.CommitHash
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.review.ReviewerCatalog
import orca.sessions.SessionStore
import orca.testkit.GitRepo
import orca.tools.OsGitTool

/** A `FlowControl` over real stores, for exercising the `stage` runtime (commit
  * + resume).
  *
  * Stage-identity bookkeeping (withStage and claimSessionKey) and the per-run
  * turn claim are inherited from the shared `StageFrames` mixin — the SAME
  * implementation production uses, so this double can't diverge from production
  * nesting/resume semantics and greenwash a test.
  */
class TestFlowControl(
    val context: TestFlowContext,
    val progressStore: ProgressStore,
    val sessionStore: SessionStore,
    private[orca] val startingCommit: Option[CommitHash] = None
) extends FlowControl,
      StageFrames

object TestFlowControl:
  /** Build a `TestFlowControl` over a fresh temp git repo (with one seed commit
    * so HEAD exists) and a default progress store seeded with a header. Its
    * context wires the repo's git and `lead`. Returns the control plus the repo
    * dir for assertions on commits/files.
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
    (
      new TestFlowControl(
        context,
        store,
        SessionStore.default(dir, runKey),
        Option.when(startingCommitUsable)(headCommit)
      ),
      dir
    )
