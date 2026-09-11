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
import orca.tools.{GitHubAvailability, GitHubTool, GitTool, OsGitTool, PrHandle}
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.testkit.GitRepo
import orca.events.{EventDispatcher, OrcaListener}

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.ConcurrentLinkedQueue

/** An endpoint the PR helpers never reach. */
private[pr] def nyi(m: String): Nothing =
  throw new NotImplementedError(s"$m unused by the PR helpers")

/** The handle [[RecordingGh.createPr]] hands back. */
private[pr] val samplePr: PrHandle =
  PrHandle("github.com", "acme", "widgets", 1)

/** Records `push`; stubs `defaultBase` and answers `diffVsBase` with
  * `branchDiff` (no remote in the temp repo); delegates the writes the `stage`
  * runtime performs (`forceAdd`, `commit`, `uncommittedDiff`) to `underlying`
  * so stage commits actually land.
  */
private[pr] class RecordingGit(
    underlying: GitTool,
    calls: ConcurrentLinkedQueue[String],
    branchDiff: String
) extends GitTool:
  export underlying.{push => _, defaultBase => _, diffVsBase => _, *}

  def push()(using WorkspaceWrite) =
    calls.add("push"): Unit
    Right(())
  def defaultBase(): String = "main"
  def diffVsBase(base: String): String = branchDiff

/** Records `availability` and `createPr`, answering the former with
  * `availabilityAnswer` and the latter with [[samplePr]]; every other endpoint
  * is unreached by the PR helpers. `availabilityAnswer` is by-name so a suite
  * whose helper never probes can pass [[nyi]].
  */
private[pr] class RecordingGh(
    calls: ConcurrentLinkedQueue[String],
    availabilityAnswer: => GitHubAvailability
) extends GitHubTool:
  def availability(): GitHubAvailability =
    calls.add("availability"): Unit
    availabilityAnswer
  def createPr(title: String, body: String)(using WorkspaceWrite) =
    calls.add("createPr"): Unit
    Right(samplePr)
  def updatePr(pr: PrHandle, title: String, body: String)(using
      WorkspaceWrite
  ) =
    nyi("updatePr")
  def readIssue(issue: orca.tools.IssueHandle) = nyi("readIssue")
  def readIssueComments(issue: orca.tools.IssueHandle) = nyi(
    "readIssueComments"
  )
  def readPrComments(pr: PrHandle) = nyi("readPrComments")
  def writeComment(pr: PrHandle, body: String)(using WorkspaceWrite) =
    nyi("writeComment")
  def writeComment(issue: orca.tools.IssueHandle, body: String)(using
      WorkspaceWrite
  ) = nyi("writeComment")
  def upsertComment(pr: PrHandle, marker: String, body: String)(using
      WorkspaceWrite
  ) = nyi("upsertComment")
  def upsertComment(
      issue: orca.tools.IssueHandle,
      marker: String,
      body: String
  )(using
      WorkspaceWrite
  ) = nyi("upsertComment")
  def buildStatus(pr: PrHandle) = nyi("buildStatus")
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration,
      noChecksGrace: FiniteDuration
  ) = nyi("waitForBuild")

/** Records the prompt it was sent and returns a fixed [[PrSummary]]. */
private[pr] class StubSummariser extends Agent[BackendTag.ClaudeCode.type]:
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
            PrSummary("Generated title", "Generated body")
              .asInstanceOf[O]
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        nyi("interactive")

/** A [[TestFlowControl]] whose `gh` is the recording double (the base stubs it)
  * and whose `git` records/delegates via [[RecordingGit]].
  */
private[pr] class PrTestControl(
    dispatcher: EventDispatcher,
    recordingGit: GitTool,
    recordingGh: GitHubTool,
    store: ProgressStore
) extends TestFlowControl(dispatcher, recordingGit, store, "p"):
  override lazy val gh: GitHubTool = recordingGh

/** A seeded repo with a run header written, ready for the PR helpers to stage
  * into. Repo and store are returned together so a second control can be built
  * over the same pair, which is how a resumed run is exercised.
  */
private[pr] def seededPrRepo(): (os.Path, ProgressStore) =
  val dir = GitRepo.seeded()
  val store = ProgressStore.default(dir, "p")
  given WorkspaceWrite = WorkspaceWrite.unsafe
  store.writeHeader(
    ProgressHeader("main", "feat/test", "deadbeef", BranchMode.Created)
  )
  (dir, store)

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
    availability: => GitHubAvailability = nyi("availability")
): FlowControl =
  new PrTestControl(
    new EventDispatcher(List(listener)),
    new RecordingGit(new OsGitTool(dir), calls, branchDiff),
    new RecordingGh(calls, availability),
    store
  )
