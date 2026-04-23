package orca.runner

import orca.{
  ClaudeTool,
  CodexTool,
  EventDispatcher,
  FlowContext,
  FsTool,
  GitHubTool,
  GitTool,
  LlmConfig,
  OrcaEvent,
  PromptTemplate
}
import orca.claude.{ClaudeBackend, DefaultClaudeTool}
import orca.io.DefaultPromptTemplate
import orca.subprocess.OsProcCliRunner
import orca.tools.{OsFsTool, OsGitHubTool, OsGitTool}

/** Production FlowContext wiring. Callers typically construct one via
  * `orca(...)`, which supplies defaults for all tools. Individual tools can be
  * replaced by passing overrides to `orca`. `codex` remains stubbed pending
  * Epic 9.
  */
class DefaultFlowContext(
    val userPrompt: String,
    dispatcher: EventDispatcher,
    val claude: ClaudeTool,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool
) extends FlowContext:

  def codex: CodexTool =
    throw new NotImplementedError("CodexTool lands with Epic 9")

  def emit(event: OrcaEvent): Unit = dispatcher.dispatch(event)

object DefaultFlowContext:

  /** Build a context with Orca's default tool implementations, filling in any
    * `None` override with the production default.
    */
  def withDefaults(
      userPrompt: String,
      dispatcher: EventDispatcher,
      workDir: os.Path,
      claude: Option[ClaudeTool] = None,
      git: Option[GitTool] = None,
      gh: Option[GitHubTool] = None,
      fs: Option[FsTool] = None,
      template: PromptTemplate = DefaultPromptTemplate
  ): DefaultFlowContext =
    new DefaultFlowContext(
      userPrompt = userPrompt,
      dispatcher = dispatcher,
      claude = claude.getOrElse(
        new DefaultClaudeTool(
          backend = new ClaudeBackend(OsProcCliRunner),
          config = LlmConfig.default,
          template = template,
          workDir = workDir,
          emit = dispatcher.dispatch
        )
      ),
      git = git.getOrElse(new OsGitTool(workDir)),
      gh = gh.getOrElse(new OsGitHubTool(OsProcCliRunner, workDir)),
      fs = fs.getOrElse(new OsFsTool(workDir))
    )
