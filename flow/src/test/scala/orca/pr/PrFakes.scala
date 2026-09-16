// Doubles shared by the `orca.pr` suites, plus the fixture that wires them
// into the real `stage` machinery over a seeded repo.
package orca.pr

import orca.{FlowControl, TestFlowControl, WorkspaceWrite}
import orca.agents.{
  Agent,
  AgentCall,
  AgentConfig,
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  SessionId,
  ToolSet
}
import orca.tools.{
  GitHubAvailability,
  GitHubTool,
  GitTool,
  NoDefaultBase,
  OsGitTool,
  PrCreateFailed,
  PrHandle,
  PushFailure
}
import orca.progress.{BranchMode, CommitHash, ProgressHeader, ProgressStore}
import orca.testkit.{GitRepo, PushlessGit, StubGitHubTool}
import orca.events.{EventDispatcher, OrcaListener}

import java.util.concurrent.ConcurrentLinkedQueue

/** An endpoint the PR helpers never reach. */
private[pr] def nyi(m: String): Nothing =
  throw new NotImplementedError(s"$m unused by the PR helpers")

/** The handle [[RecordingGh.createPr]] hands back. */
private[pr] val samplePr: PrHandle =
  PrHandle(host = "github.com", owner = "acme", repo = "widgets", number = 1)

/** [[PushlessGit]] that also records the push, so a test can pin when the PR
  * helpers pushed relative to their other calls.
  */
private[pr] class RecordingGit(
    underlying: GitTool,
    calls: ConcurrentLinkedQueue[String],
    branchDiff: String,
    pushAnswer: => Either[PushFailure, Unit],
    base: => Either[NoDefaultBase, String]
) extends PushlessGit(underlying, branchDiff, pushAnswer, base):
  override def push()(using WorkspaceWrite) =
    calls.add("push"): Unit
    super.push()

/** Records `availability` and `createPr`, answering the former with
  * `availabilityAnswer` and the latter with [[samplePr]]; every other endpoint
  * refuses, from [[StubGitHubTool]]. `availabilityAnswer` is by-name so a suite
  * whose helper never probes can pass [[nyi]].
  */
private[pr] class RecordingGh(
    calls: ConcurrentLinkedQueue[String],
    availabilityAnswer: => GitHubAvailability,
    createPrAnswer: => Either[PrCreateFailed, PrHandle] = Right(samplePr)
) extends StubGitHubTool:
  override def availability(): GitHubAvailability =
    calls.add("availability"): Unit
    availabilityAnswer
  override def createPr(title: String, body: String)(using WorkspaceWrite) =
    calls.add("createPr"): Unit
    createPrAnswer

/** Records the prompt it was sent and answers `answer` — a fixed [[PrSummary]]
  * unless a test makes the summarise stage fail.
  */
private[pr] class StubSummariser(
    answer: => PrSummary = PrSummary("Generated title", "Generated body")
) extends Agent[BackendTag.ClaudeCode.type]:
  var captured: String = ""
  val name: String = "summariser"
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
    nyi("autonomous")
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              sessionName: Option[String],
              config: Option[AgentConfig],
              emitPrompt: Boolean
          )(using in: AgentInput[I], _s: orca.InStage): O =
            captured = in.serialize(input)
            answer.asInstanceOf[O]
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        nyi("interactive")

/** A [[TestFlowControl]] whose `gh` is the recording double (the base stubs it)
  * and whose `git` records/delegates via [[RecordingGit]].
  */
private[pr] class PrTestControl(
    dispatcher: EventDispatcher,
    recordingGit: GitTool,
    recordingGh: GitHubTool,
    store: ProgressStore,
    runStartedAt: Option[CommitHash]
) extends TestFlowControl(
      dispatcher,
      recordingGit,
      store,
      "p",
      startingCommit = runStartedAt
    ):
  override lazy val gh: GitHubTool = recordingGh

/** A seeded repo on the `feat/test` branch the header names, with a run header
  * written, ready for the PR helpers to stage into. Repo and store are returned
  * together so a second control can be built over the same pair, which is how a
  * resumed run is exercised.
  *
  * `withCode` decides whether the branch carries anything but orca's own files
  * — what the PR helpers check before opening a PR for the run. `branchMode` is
  * the header's: a `Reused` header names `feat/test` as the starting branch
  * too, as a `--skip-branch` run's does. A `startBranch` other than `main` is
  * created one commit ahead of `main`, and `feat/test` branches from it — so a
  * helper measuring the run against the default base instead of its start point
  * sees that commit.
  */
private[pr] def seededPrRepo(
    withCode: Boolean = true,
    branchMode: BranchMode = BranchMode.Created,
    startBranch: String = "main"
): (os.Path, ProgressStore) =
  val dir = GitRepo.seeded()
  if startBranch != "main" then
    val _ = os.proc("git", "checkout", "-b", startBranch).call(cwd = dir)
    os.write(dir / "ahead.txt", "earlier work")
    val _ = os.proc("git", "add", "ahead.txt").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "earlier").call(cwd = dir)
  val _ = os.proc("git", "checkout", "-b", "feat/test").call(cwd = dir)
  if withCode then
    os.write(dir / "code.txt", "real code")
    val _ = os.proc("git", "add", "code.txt").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "work").call(cwd = dir)
  val store = ProgressStore.default(dir, "p")
  given WorkspaceWrite = WorkspaceWrite.unsafe
  val startingBranch = branchMode match
    case BranchMode.Created => startBranch
    case BranchMode.Reused  => "feat/test"
  store.writeHeader(
    ProgressHeader(startingBranch, "feat/test", "deadbeef", branchMode)
  )
  (dir, store)

/** The branch the run's header says it started on, which the PR helpers measure
  * "did this run change code" against.
  */
private[pr] def startBranchOf(store: ProgressStore): String =
  store.load().map(_.header.startingBranch).getOrElse("main")

/** A control over `dir`/`store` whose `git`/`gh` record into `calls` and whose
  * events reach `listener`. `availability` is only reached by a helper that
  * probes, so it defaults to refusing.
  */
private[pr] def prControl(
    dir: os.Path,
    store: ProgressStore,
    listener: OrcaListener,
    calls: ConcurrentLinkedQueue[String],
    branchDiff: String = "stub-diff",
    availability: => GitHubAvailability = nyi("availability"),
    createPr: => Either[PrCreateFailed, PrHandle] = Right(samplePr),
    push: => Either[PushFailure, Unit] = Right(()),
    base: => Either[NoDefaultBase, String] = Right("main")
): FlowControl =
  new PrTestControl(
    new EventDispatcher(List(listener)),
    new RecordingGit(new OsGitTool(dir), calls, branchDiff, push, base),
    new RecordingGh(calls, availability, createPr),
    store,
    // Where the run started, as the runtime records it: the tip of the branch
    // the header names.
    CommitHash.from(
      os.proc("git", "rev-parse", startBranchOf(store))
        .call(cwd = dir)
        .out
        .text()
        .trim
    )
  )
