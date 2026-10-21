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
import orca.tools.FsTool
import orca.tools.RuntimeGit
import orca.tools.GitHubTool

/** FlowContext stub for unit-testing stage/fail and other helpers. Tool and
  * agent accessors are lazy so merely constructing the context doesn't throw;
  * `lead` backs all three roles, and `wiredGit` / `wiredGh` the tools, for
  * tests that exercise them.
  */
class TestFlowContext(
    dispatcher: EventDispatcher,
    val userPrompt: String = "",
    val workDir: os.Path = orca.testkit.TempDirs.dir(),
    val stackSettings: StackSettings = StackSettings.empty,
    val reviewerCatalog: ReviewerCatalog = ReviewerCatalog.builtIn,
    lead: Option[Agent[BackendTag.ClaudeCode.type]] = None,
    wiredGit: Option[RuntimeGit] = None,
    wiredGh: Option[GitHubTool] = None
) extends FlowContext:
  private def stub(name: String) =
    throw new NotImplementedError(s"$name is not wired in TestFlowContext")

  lazy val planningAgent: Agent[?] = lead.getOrElse(stub("planningAgent"))
  lazy val codingAgent: Agent[?] = lead.getOrElse(stub("codingAgent"))
  lazy val reviewAgent: Agent[?] = lead.getOrElse(stub("reviewAgent"))
  lazy val claude: ClaudeAgent = stub("claude")
  lazy val codex: CodexAgent = stub("codex")
  lazy val opencode: OpencodeAgent = stub("opencode")
  lazy val pi: PiAgent = stub("pi")
  lazy val gemini: GeminiAgent = stub("gemini")
  private[orca] lazy val runtimeGit: RuntimeGit =
    wiredGit.getOrElse(stub("git"))
  lazy val gh: GitHubTool = wiredGh.getOrElse(stub("gh"))
  lazy val fs: FsTool = stub("fs")

  private[orca] def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)
