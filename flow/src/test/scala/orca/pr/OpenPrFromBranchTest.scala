package orca.pr

import munit.FunSuite
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
import orca.tools.{DiffMode, GitHubTool, GitTool, OsGitTool, PrHandle}
import orca.progress.{ProgressHeader, ProgressStore}
import orca.testkit.GitRepo
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrFromBranch]] — the push → summarise → create tail bundled
  * as three resume-safe stages.
  *
  * The helper is thin orchestration, so the honest thing to pin is its
  * STRUCTURE: three stages in the fixed order, and — the resume-critical
  * property — `git.push` happening in an earlier stage than `gh.createPr`.
  * Recording `git`/`gh` doubles capture call order; a real [[TestFlowControl]]
  * runs the actual `stage` machinery so the emitted stage boundaries are real.
  */
class OpenPrFromBranchTest extends FunSuite:

  private def nyi(m: String): Nothing =
    throw new NotImplementedError(s"$m unused by openPrFromBranch")

  /** Records `push`; stubs `diffVsBase`/`defaultBase` (no remote in the temp
    * repo); delegates the writes the `stage` runtime performs (`forceAdd`,
    * `commit`, `diff`) to a real [[OsGitTool]] so stage commits actually land.
    */
  private class RecordingGit(
      underlying: GitTool,
      calls: ConcurrentLinkedQueue[String]
  ) extends GitTool:
    export underlying.{push => _, defaultBase => _, diffVsBase => _, *}

    def push()(using WorkspaceWrite) =
      calls.add("push"): Unit
      Right(())
    def defaultBase(): String = "main"
    def diffVsBase(base: String, mode: DiffMode = DiffMode.MergeBase): String =
      "stub-diff"

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

  /** Returns a fixed [[PrSummary]] for any prompt. */
  private class StubSummariser extends Agent[BackendTag.ClaudeCode.type]:
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
            private[orca] def runWithSession[I: AgentInput](
                input: I,
                session: SessionId[BackendTag.ClaudeCode.type],
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using orca.InStage): (SessionId[BackendTag.ClaudeCode.type], O) =
              (
                session,
                PrSummary("Generated title", "Generated body").asInstanceOf[O]
              )
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

  test("openPrFromBranch runs push, summarise, create as three ordered stages"):
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.StageStarted(name) => stages.add(name): Unit
      case _                            => ()

    val dir = GitRepo.seeded()
    val store = ProgressStore.default(dir, "p")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    val recordingGit = new RecordingGit(new OsGitTool(dir), calls)
    given FlowControl = new PrTestControl(
      new EventDispatcher(List(listener)),
      recordingGit,
      new RecordingGh(calls),
      store
    )

    val handle = openPrFromBranch(
      summarisingAgent = new StubSummariser(),
      body = summary => s"${summary.body}\n\nCloses #1."
    )

    assertEquals(handle, PrHandle("acme", "widgets", 1))
    // Push before PR: the resume-critical stage split.
    assertEquals(calls.toArray.toList, List("push", "createPr"))
    assertEquals(
      stages.toArray.toList,
      List("Push branch", "Generate PR title and description", "Open PR")
    )
