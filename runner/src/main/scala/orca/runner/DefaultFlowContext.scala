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
    * still one new `val` plus one new match arm here. Written as a match over
    * `BackendTag.values` (not a literal `Map(...)`) so the exhaustiveness
    * checker — not a human — flags a sixth `BackendTag` case that this map
    * hasn't been taught about yet. Used by `close()`/`isWiredBackend`; the five
    * concretely-typed accessors (`claude`, `codex`, …) stay as the public,
    * `FlowContext`-mandated surface, and `agentFor` is unaffected (the trait
    * default already dispatches on the same five constructor vals — see
    * [[orca.FlowContext.agentFor]]).
    */
  private val agents: Map[BackendTag, Agent[?]] =
    BackendTag.values.map {
      case BackendTag.ClaudeCode => BackendTag.ClaudeCode -> claude
      case BackendTag.Codex      => BackendTag.Codex -> codex
      case BackendTag.Opencode   => BackendTag.Opencode -> opencode
      case BackendTag.Pi         => BackendTag.Pi -> pi
      case BackendTag.Gemini     => BackendTag.Gemini -> gemini
    }.toMap

  /** True when `a` IS one of the five wired agents, or was derived from one via
    * a `copyTool`-style builder (`_.claude.opus`, `.withReadOnly`, …) — checked
    * by shared [[orca.agents.Agent.backendIdentity]], compared by REFERENCE
    * (`eq`, per that method's contract), not `Agent` reference equality,
    * because a builder-derived sibling is a DIFFERENT `Agent` instance sharing
    * the SAME backend: a naive `eq` check on the `Agent`s alone would
    * false-positive-warn/double-close on the common `_.claude.opus` selector
    * pattern (complexity-review-2 10.1). The direct `eq` fallback (on the
    * `Agent`s themselves) exists for agents with no backend at all (e.g. test
    * stubs built straight on `Agent`, whose `backendIdentity` is `None`) so a
    * selector that literally returns one of the five constructor vals unchanged
    * still counts as wired even without a backend token to compare.
    */
  private def isWiredBackend(a: Agent[?]): Boolean =
    agents.values.exists: w =>
      (w: AnyRef).eq(a) ||
        a.backendIdentity.exists(ai => w.backendIdentity.exists(_ eq ai))

  /** Tear down context-owned background resources by closing every agent (each
    * delegates to its backend; all default to no-op — today only opencode holds
    * a live resource, the shared `serve` process). Runs in the flow body's
    * `finally`, before the flow scope joins its forks (see
    * [[orca.backend.AgentBackend.close]]). Per-agent best-effort: one failing
    * close must not keep the others (or the interaction) from closing.
    *
    * Also always closes the resolved lead [[agent]], UNCONDITIONALLY appended
    * to the fan-out below rather than filtered by [[isWiredBackend]] first: a
    * foreign lead (a selector like `_ => myPrebuiltAgent`, built from a
    * separate `AgentWiring`/backend — complexity-review-2 10.1) is otherwise
    * unreachable from `agents` and would leak past flow end, and a lead that
    * DOES share a wired backend (the common `_.claude.opus` pattern) just gets
    * `close()` called on it a second time — provably harmless, since every
    * backend's `close()` is idempotent (the shared `closedFlag` latches via a
    * plain `set`, opencode's teardown is CAS-guarded, and every other backend's
    * `close()` is a no-op). Skipping the check here trades a handful of
    * redundant `close()` calls for one less thing this method has to get right;
    * [[isWiredBackend]] is kept only for the warning path on [[agent]] below,
    * where a false warning (not a resource leak) is the failure mode.
    *
    * Resolving `agent` here forces its lazy val, which is itself best-effort:
    * if `agentSelector` threw on its FIRST force (the failure `flow()`'s caller
    * already saw, reported as a `SurfacedFlowFailure` before this `finally`
    * ever runs), Scala does not cache a lazy val's failed initialization —
    * referencing `agent` again re-invokes the same throwing selector. Left
    * unguarded, that re-thrown exception would escape `close()` from inside a
    * `finally ctx.close()`, which replaces (masks) the original failure already
    * propagating AND aborts the fan-out below before it closes a single wired
    * backend. Wrapping it here keeps a throwing selector to the same "degrade,
    * don't escalate" contract as every other step of `close()`.
    */
  def close(): Unit =
    val lead =
      try Some(agent)
      catch
        case NonFatal(e) =>
          log.debug(
            "lead selector re-threw during close; skipping its close",
            e
          )
          None
    val toClose = agents.values.toList ++ lead
    toClose.foreach: a =>
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
  // Resolved lazily so the selector sees a fully-built context; first forced
  // from `FlowLifecycle.run`'s setup phase, by which point `dispatcher` is
  // already live, so the foreign-selector warning below reaches the user the
  // same way any other construction-time diagnostic does (an `OrcaEvent.Step`,
  // not stderr — see the `flow` scaladoc's override-factory paragraph). A
  // selector that escapes the five wired agents (e.g. `_ => myPrebuiltAgent`,
  // built from a separate `AgentWiring`/backend) is event-blind — it never
  // reaches this run's dispatcher — so this is a loud warning, not a silent
  // fact; `close()` above still closes it to avoid a resource leak, but
  // nothing can make it observe this run's events after the fact.
  lazy val agent: Agent[B] =
    val resolved = agentSelector(this)
    if !isWiredBackend(resolved) then
      emit(
        OrcaEvent.Step(
          "warning: lead agent was not built from this flow's context — " +
            "events may not reach the terminal/cost tracker"
        )
      )
    resolved

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
    // Applying each factory here against the constructor's expected
    // concrete-agent type drives Scala's context-function auto-application —
    // see [[FlowWiring]] for why every field shares the `Ox ?=>` shape.
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
