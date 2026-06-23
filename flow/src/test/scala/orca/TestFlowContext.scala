package orca

import orca.events.{EventDispatcher, OrcaEvent}
import orca.llm.{
  ClaudeTool,
  CodexTool,
  GeminiTool,
  LlmTool,
  OpencodeTool,
  PiTool
}
import orca.progress.{ProgressHeader, ProgressStore}
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

  lazy val llm: LlmTool[?] = stub("llm")
  lazy val claude: ClaudeTool = stub("claude")
  lazy val codex: CodexTool = stub("codex")
  lazy val opencode: OpencodeTool = stub("opencode")
  lazy val pi: PiTool = stub("pi")
  lazy val gemini: GeminiTool = stub("gemini")
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

  lazy val llm: LlmTool[?] = stub("llm")
  lazy val claude: ClaudeTool = stub("claude")
  lazy val codex: CodexTool = stub("codex")
  lazy val opencode: OpencodeTool = stub("opencode")
  lazy val pi: PiTool = stub("pi")
  lazy val gemini: GeminiTool = stub("gemini")
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
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)

    val git = new OsGitTool(dir)
    val store = ProgressStore.default(dir, userPrompt)
    given InStage = InStage.unsafe
    store.writeHeader(ProgressHeader("main", "feat/test", "deadbeef"))
    (new TestFlowControl(dispatcher, git, store, userPrompt), dir)
