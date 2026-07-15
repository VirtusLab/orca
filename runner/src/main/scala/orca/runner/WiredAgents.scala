package orca.runner

import orca.AgentSet
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}
import orca.backend.AgentWiring
import orca.tools.claude.ClaudeAgents
import orca.tools.codex.CodexAgents
import orca.tools.gemini.GeminiAgents
import orca.tools.opencode.OpencodeAgents
import orca.tools.pi.PiAgents
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/** The five agents wired for one run — the [[orca.AgentSet]] the `flow(...)`
  * lead selector resolves against. Built (via [[WiredAgents.build]]) before the
  * `FlowContext` exists; the context then takes ownership of the bundle at
  * construction and forwards its accessors here.
  */
private[orca] final case class WiredAgents(
    claude: ClaudeAgent,
    codex: CodexAgent,
    opencode: OpencodeAgent,
    pi: PiAgent,
    gemini: GeminiAgent
) extends AgentSet:

  /** The five wired agents keyed by backend tag — derived once from the
    * constructor vals above, not a second source of truth: adding a backend is
    * still one new field plus one new match arm here. Written as a match over
    * `BackendTag.values` (not a literal `Map(...)`) so the exhaustiveness
    * checker — not a human — flags a sixth `BackendTag` case that this map
    * hasn't been taught about yet. The five concretely-typed accessors
    * (`claude`, `codex`, …) stay as the public, `AgentSet`-mandated surface,
    * and `agentFor` is unaffected (the trait default already dispatches on the
    * same five fields — see [[orca.AgentSet.agentFor]]).
    */
  private val byTag: Map[BackendTag, Agent[?]] =
    BackendTag.values.map {
      case BackendTag.ClaudeCode => BackendTag.ClaudeCode -> claude
      case BackendTag.Codex      => BackendTag.Codex -> codex
      case BackendTag.Opencode   => BackendTag.Opencode -> opencode
      case BackendTag.Pi         => BackendTag.Pi -> pi
      case BackendTag.Gemini     => BackendTag.Gemini -> gemini
    }.toMap

  /** Every wired agent, in the order the close fan-outs use. */
  def all: List[Agent[?]] = byTag.values.toList

  /** True when `a` IS one of the five wired agents, or was derived from one via
    * a `copyTool`-style builder (`_.claude.opus`, `.withReadOnly`, …) — checked
    * by shared [[orca.agents.Agent.backendIdentity]], compared by REFERENCE
    * (`eq`, per that method's contract), not `Agent` reference equality,
    * because a builder-derived sibling is a DIFFERENT `Agent` instance sharing
    * the SAME backend: a naive `eq` check on the `Agent`s alone would
    * false-positive-warn on the common `_.claude.opus` selector pattern. The
    * direct `eq` fallback (on the `Agent`s themselves) exists for agents with
    * no backend at all (e.g. test stubs built straight on `Agent`, whose
    * `backendIdentity` is `None`) so a selector that literally returns one of
    * the five wired agents unchanged still counts as wired even without a
    * backend token to compare. Used only for the foreign-lead warning at
    * selector-resolution time, where a false warning (not a resource leak) is
    * the failure mode — both close fan-outs skip the check and close the lead
    * unconditionally.
    */
  def isWiredBackend(a: Agent[?]): Boolean =
    byTag.values.exists: w =>
      (w: AnyRef).eq(a) ||
        a.backendIdentity.exists(ai => w.backendIdentity.exists(_ eq ai))

private[orca] object WiredAgents:

  private val log = LoggerFactory.getLogger(getClass)

  /** Wire the run's agents, filling every `None` override with the production
    * default. Every factory is applied against `agentWiring` — the run's single
    * bundle of event sink, interaction, workDir and prompts — so a user agent
    * is wired into the run exactly like a default one. The default configs
    * (Opus1M/Pro pins) live in the per-backend `*Agents.default` factories, the
    * single source of truth. Applying each factory against the field's expected
    * concrete-agent type drives Scala's context-function auto-application — see
    * [[FlowWiring]] for why every field shares the `Ox ?=>` shape.
    */
  def build(wiring: FlowWiring, agentWiring: AgentWiring)(using
      ox.Ox
  ): WiredAgents =
    WiredAgents(
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
        .getOrElse(GeminiAgents.default(agentWiring))
    )

  /** Best-effort close fan-out over `agents` (each delegates to its backend;
    * all default to no-op — today only opencode holds a live resource, the
    * shared `serve` process). Per-agent best-effort: one failing close must not
    * keep the others from closing. Shared by `DefaultFlowContext.close()` and
    * `runFlow`'s pre-construction ownership guard so both close the same way,
    * in the same order.
    */
  def closeBestEffort(agents: List[Agent[?]]): Unit =
    agents.foreach: a =>
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
