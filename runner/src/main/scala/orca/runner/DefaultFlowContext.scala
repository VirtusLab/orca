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
import org.slf4j.LoggerFactory

import ox.discard
import scala.util.control.NonFatal

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
    val progressStore: ProgressStore
) extends FlowControl:

  private val log = LoggerFactory.getLogger(getClass)

  /** Tear down context-owned background resources by closing every agent (each
    * delegates to its backend; all default to no-op — today only opencode holds
    * a live resource, the shared `serve` process). Runs in the flow body's
    * `finally`, before the flow scope joins its forks (see
    * [[orca.backend.AgentBackend.close]]). Per-agent best-effort: one failing
    * close must not keep the others (or the interaction) from closing.
    */
  def close(): Unit =
    List(claude, codex, opencode, pi, gemini).foreach: a =>
      try a.close()
      catch
        case NonFatal(e) =>
          log.error(
            "agent close failed — a backend resource may have leaked",
            e
          )
          System.err.println(
            s"[orca] failed to close ${a.getClass.getSimpleName} (a backend " +
              s"resource may have leaked): ${e.getMessage}"
          )

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

  // Written possibly from fork threads (`fail` inside a parallel block), read on
  // the stage thread during unwind — pure atomic state, per the concurrency
  // conventions. Identity comparison: the mark belongs to the object instance.
  private val reportedErrors =
    new java.util.concurrent.atomic.AtomicReference[List[Throwable]](Nil)
  private[orca] def markErrorReported(e: Throwable): Unit =
    reportedErrors.updateAndGet(e :: _).discard
  private[orca] def errorAlreadyReported(e: Throwable): Boolean =
    reportedErrors.get().exists(_ eq e)

  // Reached only through FlowControl, which is thread-affine by R12 (ADR 0018
  // §2.2) — stages and session(...) calls never run concurrently, so a plain
  // var states the real invariant where a concurrent map would falsely
  // advertise cross-thread sharing.
  private var occurrences: Map[String, Int] = Map.empty

  def nextOccurrence(stageName: String): Int =
    val n = occurrences.getOrElse(stageName, 0)
    occurrences = occurrences.updated(stageName, n + 1)
    n

  // Independent of the stage counter so sessions can be obtained outside stages
  // without perturbing stage occurrence indices. Keyed per-name, mirroring
  // `occurrences` above.
  private var sessionOccurrences: Map[String, Int] = Map.empty

  def nextSessionOccurrence(name: String): Int =
    val n = sessionOccurrences.getOrElse(name, 0)
    sessionOccurrences = sessionOccurrences.updated(name, n + 1)
    n

private[orca] object DefaultFlowContext:

  /** Build a context with Orca's default tool implementations, filling in any
    * `None` override with the production default. `close()` on the resulting
    * context closes all five agents (see [[DefaultFlowContext.close]]) —
    * including caller-supplied ones: the no-op default on [[Agent.close]] makes
    * this behaviour-preserving for a caller who didn't override it, and a
    * caller whose agent DOES override `close` presumably wants it called.
    */
  def withDefaults[B <: BackendTag](
      userPrompt: String,
      dispatcher: EventDispatcher,
      workDir: os.Path,
      interaction: Interaction,
      progressStore: ProgressStore,
      agentSelector: FlowContext => Agent[B],
      wiring: FlowWiring
  )(using ox.Ox, ox.channels.BufferCapacity): DefaultFlowContext[B] =
    val prompts = wiring.prompts
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
      opencode = wiring.opencode.getOrElse(
        new DefaultOpencodeAgent(
          backend =
            OpencodeBackend(OsProcCliRunner, workDir, wiring.opencodeLauncher),
          config = AgentConfig.default,
          prompts = prompts,
          workDir = workDir,
          events = dispatcher,
          interaction = interaction
        )
      ),
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
      progressStore = progressStore
    )
