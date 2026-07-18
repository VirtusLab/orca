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
  * `FlowContext` exists; the context then takes ownership of the bundle.
  */
private[orca] final class WiredAgents(
    val claude: ClaudeAgent,
    val codex: CodexAgent,
    val opencode: OpencodeAgent,
    val pi: PiAgent,
    val gemini: GeminiAgent
) extends AgentSet:

  /** The five wired agents keyed by backend tag, derived from the constructor
    * vals. Written as a match over `BackendTag.values` (not a literal
    * `Map(...)`) so the exhaustiveness checker flags a sixth `BackendTag` case
    * this map hasn't been taught about.
    */
  private val byTag: Map[BackendTag, Agent[?]] =
    BackendTag.values.map {
      case BackendTag.ClaudeCode => BackendTag.ClaudeCode -> claude
      case BackendTag.Codex      => BackendTag.Codex -> codex
      case BackendTag.Opencode   => BackendTag.Opencode -> opencode
      case BackendTag.Pi         => BackendTag.Pi -> pi
      case BackendTag.Gemini     => BackendTag.Gemini -> gemini
    }.toMap

  /** Every wired agent (unordered — both close fan-outs are order-independent).
    */
  def all: List[Agent[?]] = byTag.values.toList

  /** True when `a` IS one of the five wired agents, or was derived from one via
    * a builder (`_.claude.opus`, `.withReadOnly`, …). A builder-derived sibling
    * is a different `Agent` instance sharing the same backend, so the primary
    * test compares shared [[orca.agents.Agent.backendIdentity]] by `eq`; a
    * naive `eq` on the `Agent`s alone would false-positive-warn on
    * `_.claude.opus`. The direct `eq` fallback covers agents with no backend
    * (e.g. test stubs, whose `backendIdentity` is `None`). Used only for the
    * foreign-lead warning, where a false warning (not a leak) is the failure
    * mode.
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
    * is wired into the run exactly like a default one. Default configs live in
    * the per-backend `*Agents.default` factories. See [[FlowWiring]] for why
    * every field shares the `Ox ?=>` shape.
    */
  def build(wiring: FlowWiring, agentWiring: AgentWiring)(using
      ox.Ox
  ): WiredAgents =
    new WiredAgents(
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
    * today only opencode holds a live resource, the shared `serve` process).
    * One failing close must not keep the others from closing. Shared by
    * `DefaultFlowContext.close()` and `runFlow`'s pre-construction ownership
    * guard so both close the same way.
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
