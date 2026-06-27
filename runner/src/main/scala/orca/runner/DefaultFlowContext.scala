package orca.runner

import orca.{FlowContext, FlowControl, InStage}
import orca.progress.ProgressStore
import orca.tools.{GitTool}
import orca.tools.{GitHubTool}
import orca.tools.{FsTool}
import orca.agents.{
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  AgentConfig,
  Agent,
  OpencodeAgent,
  PiAgent,
  Prompts
}
import orca.events.{EventDispatcher, OrcaEvent}

import orca.backend.Interaction
import orca.tools.claude.{ClaudeBackend, DefaultClaudeAgent}
import orca.tools.codex.{CodexBackend, DefaultCodexAgent}
import orca.tools.opencode.{
  DefaultOpencodeAgent,
  OpencodeBackend,
  OpencodeLauncher
}
import orca.tools.pi.{DefaultPiAgent, PiBackend}
import orca.tools.gemini.{GeminiBackend, DefaultGeminiAgent}
import orca.agents.DefaultPrompts
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
    agentSelector: FlowContext => Agent[?],
    val claude: ClaudeAgent,
    val codex: CodexAgent,
    val opencode: OpencodeAgent,
    val pi: PiAgent,
    val gemini: GeminiAgent,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool,
    val progressStore: ProgressStore
) extends FlowControl:

  // The leading agent, resolved by the selector against this context (the only
  // way to name an agent is an accessor on it — `_.claude`, `_.codex`, …).
  // Resolved lazily so the selector sees a fully-built context. Kept PRIVATE and
  // unexposed: flow bodies reach the lead via the typed `agent` accessor, and
  // the runtime threads its own copy (`runFlow` re-applies the selector for
  // branch setup / the `Lead` carrier). The only thing the context surfaces is
  // `cheapOneShot`, the incidental-text capability the in-stage commit path needs.
  private lazy val lead: Agent[?] = agentSelector(this)

  private[orca] def cheapOneShot(prompt: String, fallback: => String)(using
      InStage
  ): String =
    lead.cheapOneShot(prompt, fallback)

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
      agentSelector: FlowContext => Agent[?],
      claude: Option[ClaudeAgent] = None,
      codex: Option[CodexAgent] = None,
      opencode: Option[OpencodeAgent] = None,
      opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
      pi: Option[PiAgent] = None,
      gemini: Option[GeminiAgent] = None,
      git: Option[GitTool] = None,
      gh: Option[GitHubTool] = None,
      fs: Option[FsTool] = None,
      prompts: Prompts = DefaultPrompts
  )(using ox.Ox, ox.channels.BufferCapacity): DefaultFlowContext =
    new DefaultFlowContext(
      userPrompt = userPrompt,
      dispatcher = dispatcher,
      agentSelector = agentSelector,
      claude = claude.getOrElse(
        new DefaultClaudeAgent(
          backend = new ClaudeBackend(OsProcCliRunner),
          // Bare `claude` defaults to Opus with the 1M context window — the
          // implementer session is long-lived, so it needs the big window.
          // `claude.sonnet` / `claude.haiku` opt down for cheap one-shots.
          config =
            AgentConfig.default.copy(model = Some(DefaultClaudeAgent.Opus1M)),
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      codex = codex.getOrElse(
        new DefaultCodexAgent(
          backend = new CodexBackend(OsProcCliRunner),
          config = AgentConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      opencode = opencode.getOrElse(
        new DefaultOpencodeAgent(
          backend = OpencodeBackend(OsProcCliRunner, opencodeLauncher),
          config = AgentConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      pi = pi.getOrElse(
        new DefaultPiAgent(
          backend = new PiBackend(OsProcCliRunner),
          config = AgentConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      gemini = gemini.getOrElse(
        new DefaultGeminiAgent(
          backend = new GeminiBackend(OsProcCliRunner),
          // Bare `gemini` pins Gemini Pro (the strong model, like claude
          // defaults to Opus for the long-lived implementer); `gemini.flash`
          // opts down for cheap one-shots.
          config =
            AgentConfig.default.copy(model = Some(DefaultGeminiAgent.Pro)),
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
