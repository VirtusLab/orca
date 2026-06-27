package orca

import orca.events.{EventDispatcher, OrcaEvent}
import orca.agents.{
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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Minimal FlowContext stub for unit-testing stage/fail and other helpers that
  * only touch `emit` + `userPrompt`. Tool accessors are lazy so merely
  * constructing the context doesn't throw; tests that exercise them should
  * provide real (or stubbed) implementations.
  */
class TestFlowContext(
    dispatcher: EventDispatcher,
    val userPrompt: String = ""
) extends FlowContext:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowContext")

  def cheapOneShot(prompt: String, fallback: => String)(using InStage): String =
    fallback
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
) extends FlowControl:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowControl")

  def cheapOneShot(prompt: String, fallback: => String)(using InStage): String =
    fallback
  lazy val claude: ClaudeAgent = stub("claude")
  lazy val codex: CodexAgent = stub("codex")
  lazy val opencode: OpencodeAgent = stub("opencode")
  lazy val pi: PiAgent = stub("pi")
  lazy val gemini: GeminiAgent = stub("gemini")
  lazy val gh: GitHubTool = stub("gh")
  lazy val fs: FsTool = stub("fs")

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

  private val occurrences = new ConcurrentHashMap[String, AtomicInteger]
  def nextOccurrence(stageName: String): Int =
    occurrences
      .computeIfAbsent(stageName, _ => new AtomicInteger(0))
      .getAndIncrement()

  private val sessionOccurrences = new AtomicInteger(0)
  def nextSessionOccurrence(): Int = sessionOccurrences.getAndIncrement()

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
    given InStage = InStage.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    (new TestFlowControl(dispatcher, git, store, userPrompt), dir)
