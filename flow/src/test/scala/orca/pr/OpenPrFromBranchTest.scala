package orca.pr

import munit.FunSuite
import orca.{BoundedDiff, FlowControl, TestFlowControl, WorkspaceWrite}
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
import orca.tools.{GitHubTool, GitTool, OsGitTool, PrHandle}
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.testkit.GitRepo
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrFromBranch]] — push → summarise → create as three
  * resume-safe stages. Pins the structure: three stages in fixed order, with
  * the resume-critical property that `git.push` runs in an earlier stage than
  * `gh.createPr`. Recording `git`/`gh` doubles capture call order; a real
  * [[TestFlowControl]] runs the actual `stage` machinery so the emitted stage
  * boundaries are real. Also pins that the branch diff reaches the summariser
  * bounded; how it is cut is [[orca.BoundedDiffTest]].
  */
class OpenPrFromBranchTest extends FunSuite:

  private def nyi(m: String): Nothing =
    throw new NotImplementedError(s"$m unused by openPrFromBranch")

  /** Records `push`; stubs `defaultBase` and answers `diffVsBase` with
    * `branchDiff` (no remote in the temp repo); delegates the writes the
    * `stage` runtime performs (`forceAdd`, `commit`, `uncommittedDiff`) to a
    * real [[OsGitTool]] so stage commits actually land.
    */
  private class RecordingGit(
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

  /** Records `createPr` and hands back a fixed handle; every other endpoint is
    * unreached by `openPrFromBranch`.
    */
  private class RecordingGh(calls: ConcurrentLinkedQueue[String])
      extends GitHubTool:
    def createPr(title: String, body: String)(using WorkspaceWrite) =
      calls.add("createPr"): Unit
      Right(PrHandle("acme", "widgets", 1))
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
  private class StubSummariser extends Agent[BackendTag.ClaudeCode.type]:
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
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using in: AgentInput[I], _s: orca.InStage): O =
              captured = in.serialize(input)
              PrSummary("Generated title", "Generated body")
                .asInstanceOf[O]
        def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
          nyi("interactive")

  /** A [[TestFlowControl]] whose `gh` is the recording double (the base stubs
    * it) and whose `git` records/delegates via [[RecordingGit]].
    */
  private class PrTestControl(
      dispatcher: EventDispatcher,
      recordingGit: GitTool,
      recordingGh: GitHubTool,
      store: ProgressStore
  ) extends TestFlowControl(dispatcher, recordingGit, store, "p"):
    override lazy val gh: GitHubTool = recordingGh

  /** What one flow run against a branch diff of `branchDiff` produced. */
  private case class Run(
      handle: PrHandle,
      calls: List[String],
      stages: List[String],
      prompt: String
  )

  private def run(branchDiff: String): Run =
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.StageStarted(name) => stages.add(name): Unit
      case _                            => ()

    val dir = GitRepo.seeded()
    val store = ProgressStore.default(dir, "p")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(
      ProgressHeader("main", "feat/test", "deadbeef", BranchMode.Created)
    )
    val summariser = new StubSummariser()
    given FlowControl = new PrTestControl(
      new EventDispatcher(List(listener)),
      new RecordingGit(new OsGitTool(dir), calls, branchDiff),
      new RecordingGh(calls),
      store
    )

    val handle = openPrFromBranch(
      summarisingAgent = summariser,
      body = summary => s"${summary.body}\n\nCloses #1."
    )
    Run(
      handle,
      calls.asScala.toList,
      stages.asScala.toList,
      summariser.captured
    )

  test("openPrFromBranch runs push, summarise, create as three ordered stages"):
    val r = run("stub-diff")
    assertEquals(r.handle, PrHandle("acme", "widgets", 1))
    // Push before PR: the resume-critical stage split.
    assertEquals(r.calls, List("push", "createPr"))
    assertEquals(
      r.stages,
      List("Push branch", "Generate PR title and description", "Open PR")
    )

  test("a branch too large to summarise reaches the agent cut short"):
    // Unbounded, this is the prompt no context window takes, and it is rebuilt
    // on every re-run — the push stage has already committed by then.
    val diff = "+" * (BoundedDiff.ReviewThreshold * 2)
    val r = run(diff)
    assert(!r.prompt.contains(diff), "the whole branch diff was sent")
    assert(r.prompt.contains("[diff cut at "), "the cut went unmarked")
