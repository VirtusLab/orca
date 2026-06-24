package orca.runner

import orca.{FlowContext, FlowControl}
import orca.progress.ProgressStore
import orca.tools.{GitTool}
import orca.tools.{GitHubTool}
import orca.tools.{FsTool}
import orca.llm.{
  ClaudeTool,
  CodexTool,
  GeminiTool,
  LlmConfig,
  LlmTool,
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
    leadModel: FlowContext => LlmTool[?],
    val claude: ClaudeTool,
    val codex: CodexTool,
    val opencode: OpencodeTool,
    val pi: PiTool,
    val gemini: GeminiTool,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool,
    val progressStore: ProgressStore
) extends FlowControl:

  // The leading model is named by a selector resolved against this context (the
  // only way to name a model is an accessor on the context — `_.claude`,
  // `_.codex`, …). Resolved lazily so the selector sees a fully-built context;
  // the result is the run's `llm`, used by branch setup and the body.
  //
  // WARNING: the selector MUST NOT read `ctx.llm` — that would recurse on this
  // lazy val and loop. The built-in selectors (`_.claude`, `_.codex`, …) are
  // safe because they read a concrete accessor, not `llm` itself.
  lazy val llm: LlmTool[?] = leadModel(this)

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)

  // Per-run occurrence counter. A ConcurrentHashMap + AtomicInteger is pure
  // atomic state (no class-level `var`); stages run sequentially, so this is
  // really just sequential bookkeeping made safe by construction.
  private val occurrences =
    new java.util.concurrent.ConcurrentHashMap[
      String,
      java.util.concurrent.atomic.AtomicInteger
    ]

  def nextOccurrence(stageName: String): Int =
    occurrences
      .computeIfAbsent(
        stageName,
        _ => new java.util.concurrent.atomic.AtomicInteger(0)
      )
      .getAndIncrement()

  // Independent of the stage counter so sessions can be obtained outside stages
  // without perturbing stage occurrence indices.
  private val sessionOccurrences =
    new java.util.concurrent.atomic.AtomicInteger(0)

  def nextSessionOccurrence(): Int = sessionOccurrences.getAndIncrement()

private[orca] object DefaultFlowContext:

  /** Build a context with Orca's default tool implementations, filling in any
    * `None` override with the production default.
    */
  def withDefaults(
      userPrompt: String,
      dispatcher: EventDispatcher,
      workDir: os.Path,
      interaction: Interaction,
      progressStore: ProgressStore,
      leadModel: FlowContext => LlmTool[?],
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
      leadModel = leadModel,
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
      fs = fs.getOrElse(new OsFsTool(workDir)),
      progressStore = progressStore
    )
