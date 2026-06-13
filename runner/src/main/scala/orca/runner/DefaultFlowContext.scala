package orca.runner

import orca.{FlowContext}
import orca.tools.{GitTool}
import orca.tools.{GitHubTool}
import orca.tools.{FsTool}
import orca.llm.{
  ClaudeTool,
  CodexTool,
  GeminiTool,
  LlmConfig,
  OpencodeTool,
  PiTool,
  Prompts
}
import orca.events.{EventDispatcher, OrcaEvent}

import orca.backend.Interaction
import orca.tools.claude.{ClaudeBackend, DefaultClaudeTool}
import orca.tools.codex.{CodexBackend, DefaultCodexTool}
import orca.tools.opencode.{
  DefaultOpencodeTool,
  OpencodeBackend,
  OpencodeLauncher
}
import orca.tools.pi.{DefaultPiTool, PiBackend}
import orca.tools.gemini.{GeminiBackend, DefaultGeminiTool}
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
    val opencode: OpencodeTool,
    val pi: PiTool,
    val gemini: GeminiTool,
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
      opencode: Option[OpencodeTool] = None,
      opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
      pi: Option[PiTool] = None,
      gemini: Option[GeminiTool] = None,
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
          // Bare `claude` defaults to Opus with the 1M context window — the
          // implementer session is long-lived, so it needs the big window.
          // `claude.sonnet` / `claude.haiku` opt down for cheap one-shots.
          config =
            LlmConfig.default.copy(model = Some(DefaultClaudeTool.Opus1M)),
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
      opencode = opencode.getOrElse(
        new DefaultOpencodeTool(
          backend = OpencodeBackend(OsProcCliRunner, opencodeLauncher),
          config = LlmConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      pi = pi.getOrElse(
        new DefaultPiTool(
          backend = new PiBackend(OsProcCliRunner),
          config = LlmConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      gemini = gemini.getOrElse(
        new DefaultGeminiTool(
          backend = new GeminiBackend(OsProcCliRunner),
          // Bare `gemini` pins Gemini Pro (the strong model, like claude
          // defaults to Opus for the long-lived implementer); `gemini.flash`
          // opts down for cheap one-shots.
          config = LlmConfig.default.copy(model = Some(DefaultGeminiTool.Pro)),
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
