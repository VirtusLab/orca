package orca.runner

import orca.{FlowContext}
import orca.tools.{GitTool}
import orca.tools.{GitHubTool}
import orca.tools.{FsTool}
import orca.llm.{ClaudeTool, CodexTool, LlmConfig, Prompts}
import orca.events.{EventDispatcher, OrcaEvent}

import orca.backend.Interaction
import orca.tools.claude.{ClaudeBackend, DefaultClaudeTool}
import orca.tools.codex.{CodexBackend, DefaultCodexTool}
import orca.llm.DefaultPrompts
import orca.subprocess.OsProcCliRunner
import orca.tools.OsFsTool
import orca.tools.OsGitTool
import orca.tools.OsGitHubTool

/** Production FlowContext wiring. Callers typically construct one via
  * `flow(args, ...)`, which supplies defaults for all tools. Individual tools
  * can be replaced by passing overrides as named arguments to `flow`.
  */
private[orca] class DefaultFlowContext(
    val userPrompt: String,
    dispatcher: EventDispatcher,
    val claude: ClaudeTool,
    val codex: CodexTool,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool
) extends FlowContext:

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

private[orca] object DefaultFlowContext:

  /** Build a context with Orca's default tool implementations, filling in any
    * `None` override with the production default.
    */
  def withDefaults(
      userPrompt: String,
      dispatcher: EventDispatcher,
      workDir: os.Path,
      interaction: Interaction,
      claude: Option[ClaudeTool] = None,
      codex: Option[CodexTool] = None,
      git: Option[GitTool] = None,
      gh: Option[GitHubTool] = None,
      fs: Option[FsTool] = None,
      prompts: Prompts = DefaultPrompts
  )(using ox.Ox, ox.channels.BufferCapacity): DefaultFlowContext =
    new DefaultFlowContext(
      userPrompt = userPrompt,
      dispatcher = dispatcher,
      claude = claude.getOrElse(
        new DefaultClaudeTool(
          backend = new ClaudeBackend(OsProcCliRunner),
          config = LlmConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      codex = codex.getOrElse(
        new DefaultCodexTool(
          backend = new CodexBackend(OsProcCliRunner),
          config = LlmConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      git = git.getOrElse(new OsGitTool(workDir, dispatcher)),
      gh = gh.getOrElse(
        new OsGitHubTool(OsProcCliRunner, workDir, events = dispatcher)
      ),
      fs = fs.getOrElse(new OsFsTool(workDir))
    )
