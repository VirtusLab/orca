package orca.runner

import orca.{FlowContext, FlowControl}
import orca.progress.ProgressStore
import orca.tools.{GitTool}
import orca.tools.{GitHubTool}
import orca.tools.{FsTool}
import orca.agents.{
  Agent,
  AgentConfig,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}
import orca.events.{EventDispatcher, OrcaEvent}

import orca.backend.Interaction
import orca.tools.claude.{ClaudeBackend, DefaultClaudeAgent}
import orca.tools.codex.{CodexBackend, DefaultCodexAgent}
import orca.tools.opencode.{DefaultOpencodeAgent, OpencodeBackend}
import orca.tools.pi.{DefaultPiAgent, PiBackend}
import orca.tools.gemini.{GeminiBackend, DefaultGeminiAgent}
import orca.subprocess.OsProcCliRunner
import orca.tools.OsFsTool
import orca.tools.OsGitTool
import orca.tools.OsGitHubTool

/** Production FlowContext wiring. Callers typically construct one via
  * `flow(args, ...)`, which supplies defaults for all tools. Individual tools
  * can be replaced by passing overrides as named arguments to `flow`.
  */
private[orca] class DefaultFlowContext[B <: BackendTag](
    val userPrompt: String,
    dispatcher: EventDispatcher,
    agentSelector: FlowContext => Agent[B],
    val claude: ClaudeAgent,
    val codex: CodexAgent,
    val opencode: OpencodeAgent,
    val pi: PiAgent,
    val gemini: GeminiAgent,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool,
    val progressStore: ProgressStore,
    closeHook: () => Unit = () => ()
) extends FlowControl:

  /** Tear down context-owned background resources (currently the shared
    * opencode `serve` process and its drain forks). The runner calls this in
    * the flow body's `finally`, BEFORE the flow scope joins its forks — Ox runs
    * `releaseAfterScope` finalizers after the join, so a fork blocked on a
    * non-interruptible read must be unblocked here (by destroying the process)
    * rather than via `releaseAfterScope`. Idempotent / no-op when nothing
    * started.
    */
  def close(): Unit = closeHook()

  // The leading agent's backend tag, pinned from the type parameter `B` (which
  // `flow` inferred from the selector). Concrete here, so `agent` is concretely
  // typed and sessions thread; abstract when the context is seen as `FlowContext`
  // in a body, where the path-dependent `ctx.LeadB` is still stable.
  type LeadB = B

  // The leading agent, resolved by the selector against this context (the only
  // way to name an agent is an accessor on it — `_.claude`, `_.codex`, …).
  // Resolved lazily so the selector sees a fully-built context.
  lazy val agent: Agent[B] = agentSelector(this)

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
  // without perturbing stage occurrence indices. Keyed per-name, mirroring
  // `occurrences` above.
  private val sessionOccurrences =
    new java.util.concurrent.ConcurrentHashMap[
      String,
      java.util.concurrent.atomic.AtomicInteger
    ]

  def nextSessionOccurrence(name: String): Int =
    sessionOccurrences
      .computeIfAbsent(
        name,
        _ => new java.util.concurrent.atomic.AtomicInteger(0)
      )
      .getAndIncrement()

private[orca] object DefaultFlowContext:

  /** Build a context with Orca's default tool implementations, filling in any
    * `None` override with the production default.
    */
  def withDefaults[B <: BackendTag](
      userPrompt: String,
      dispatcher: EventDispatcher,
      workDir: os.Path,
      interaction: Interaction,
      progressStore: ProgressStore,
      agentSelector: FlowContext => Agent[B],
      wiring: FlowWiring = FlowWiring()
  )(using ox.Ox, ox.channels.BufferCapacity): DefaultFlowContext[B] =
    val prompts = wiring.prompts
    // Build the opencode agent up-front so the default backend's `shutdown` can
    // be wired into the context's `close` (the runner calls it in the flow body's
    // finally). A caller-supplied opencode agent owns its own lifecycle, so the
    // hook is a no-op then.
    val (opencodeAgent, opencodeClose): (OpencodeAgent, () => Unit) =
      wiring.opencode match
        case Some(a) => (a, () => ())
        case None =>
          val backend =
            OpencodeBackend(OsProcCliRunner, wiring.opencodeLauncher)
          val a = new DefaultOpencodeAgent(
            backend = backend,
            config = AgentConfig.default,
            prompts = prompts,
            workDir = workDir,
            events = dispatcher,
            interaction = interaction
          )
          (a, () => backend.shutdown())
    new DefaultFlowContext[B](
      userPrompt = userPrompt,
      dispatcher = dispatcher,
      agentSelector = agentSelector,
      claude = wiring.claude.getOrElse(
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
      codex = wiring.codex.getOrElse(
        new DefaultCodexAgent(
          backend = new CodexBackend(OsProcCliRunner),
          config = AgentConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      opencode = opencodeAgent,
      pi = wiring.pi.getOrElse(
        new DefaultPiAgent(
          backend = new PiBackend(OsProcCliRunner),
          config = AgentConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
      gemini = wiring.gemini.getOrElse(
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
      git = wiring.git.getOrElse(new OsGitTool(workDir, dispatcher)),
      gh = wiring.gh.getOrElse(
        new OsGitHubTool(OsProcCliRunner, workDir, events = dispatcher)
      ),
      fs = wiring.fs.getOrElse(new OsFsTool(workDir)),
      progressStore = progressStore,
      closeHook = opencodeClose
    )
