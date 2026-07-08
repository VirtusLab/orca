package orca.runner

import orca.{FlowContext, FlowControl}
import orca.progress.ProgressStore
import orca.tools.{GitTool}
import orca.tools.{GitHubTool}
import orca.tools.{FsTool}
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}
import orca.events.{EventDispatcher, OrcaEvent}

import orca.backend.{AgentWiring, Interaction}
import orca.tools.claude.ClaudeAgents
import orca.tools.codex.CodexAgents
import orca.tools.opencode.OpencodeAgents
import orca.tools.pi.PiAgents
import orca.tools.gemini.GeminiAgents
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
) extends FlowControl,
      orca.StageFrames:

  private val log = LoggerFactory.getLogger(getClass)

  /** The five wired agents keyed by backend tag — derived once from the
    * constructor vals above, not a second source of truth: adding a backend is
    * still one new `val` plus one new entry here, but every consumer that used
    * to enumerate all five by hand (`close()`, [[agentFor]]) now reads this
    * instead. The five concretely-typed accessors (`claude`, `codex`, …) stay
    * as the public, `FlowContext`-mandated surface; this map is `private`
    * plumbing built from them.
    */
  private val agents: Map[BackendTag, Agent[?]] = Map(
    BackendTag.ClaudeCode -> claude,
    BackendTag.Codex -> codex,
    BackendTag.Opencode -> opencode,
    BackendTag.Pi -> pi,
    BackendTag.Gemini -> gemini
  )

  /** Tear down context-owned background resources by closing every agent (each
    * delegates to its backend; all default to no-op — today only opencode holds
    * a live resource, the shared `serve` process). Runs in the flow body's
    * `finally`, before the flow scope joins its forks (see
    * [[orca.backend.AgentBackend.close]]). Per-agent best-effort: one failing
    * close must not keep the others (or the interaction) from closing.
    */
  def close(): Unit =
    agents.values.foreach: a =>
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

  /** [[FlowContext.agentFor]] backed by the derived map above instead of the
    * trait's default per-case match — same result (the five constructor vals
    * are already realised here, so there's no laziness to preserve), one fewer
    * independent enumeration to keep in sync.
    */
  override private[orca] def agentFor(tag: BackendTag): Agent[?] = agents(tag)

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

  // Stage-identity bookkeeping (enterStage/exitStage/inStage and
  // nextSessionOccurrence) comes from the shared `StageFrames` mixin — the
  // single source of truth for the hierarchical frame-stack semantics, so the
  // test doubles cannot drift from production.

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
  )(using ox.Ox): DefaultFlowContext[B] =
    // One wiring bundle handed to every agent factory — overrides and defaults
    // build against the SAME event sink (dispatcher), interaction, workDir and
    // prompts, so a user agent is wired into the run exactly like the default
    // (complexity-review 7.8). The default configs (Opus1M/Pro pins) live in
    // the per-backend `*Agents.default` factories, the single source of truth.
    val agentWiring = AgentWiring(
      events = dispatcher,
      interaction = interaction,
      workDir = workDir,
      prompts = wiring.prompts
    )
    // Every factory field is now `AgentWiring => Ox ?=> Agent` (unified in
    // 10.2), so applying it here against the constructor's expected
    // concrete-agent type drives Scala's context-function auto-application
    // uniformly across all five — no per-field ascription needed, unlike the
    // old opencode-only `: OpencodeAgent` trick.
    new DefaultFlowContext[B](
      userPrompt = userPrompt,
      dispatcher = dispatcher,
      agentSelector = agentSelector,
      claude = wiring.claude
        .map(_(agentWiring))
        .getOrElse(ClaudeAgents.default(agentWiring)),
      codex = wiring.codex
        .map(_(agentWiring))
        .getOrElse(CodexAgents.default(agentWiring)),
      opencode = wiring.opencode
        .map(_(agentWiring))
        .getOrElse(OpencodeAgents.default(agentWiring)),
      pi =
        wiring.pi.map(_(agentWiring)).getOrElse(PiAgents.default(agentWiring)),
      gemini = wiring.gemini
        .map(_(agentWiring))
        .getOrElse(GeminiAgents.default(agentWiring)),
      git = wiring.git.getOrElse(new OsGitTool(workDir, dispatcher)),
      gh = wiring.gh.getOrElse(
        new OsGitHubTool(OsProcCliRunner, workDir, events = dispatcher)
      ),
      fs = wiring.fs.getOrElse(new OsFsTool(workDir)),
      progressStore = progressStore
    )
