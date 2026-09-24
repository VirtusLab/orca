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
import ox.{ResourceScope, releaseAfterScope}

/** The five agents wired for one run — the [[orca.AgentSet]] the `flow(...)`
  * lead selector resolves against. Built (via [[WiredAgents.build]]) before the
  * `FlowContext` exists; the run's resource scope closes them (see
  * [[WiredAgents.closeAfterScope]]).
  */
private[orca] final class WiredAgents(
    val claude: ClaudeAgent,
    val codex: CodexAgent,
    val opencode: OpencodeAgent,
    val pi: PiAgent,
    val gemini: GeminiAgent
) extends AgentSet:

  /** The five wired agents keyed by backend tag, derived from
    * [[AgentSet.agentFor]] — the single place the tag-to-agent mapping is
    * written out — rather than restating it here.
    */
  private val byTag: Map[BackendTag, Agent[?]] =
    BackendTag.values.map(t => t -> agentFor(t)).toMap

  /** Every wired agent (unordered — both close fan-outs are order-independent).
    */
  def all: List[Agent[?]] = byTag.values.toList

  /** True when `a` runs on one of the five wired backends — a wired agent
    * itself or a builder-derived sibling (`_.claude.opus`, `.withReadOnly`, …).
    * Used only for the foreign-lead warning.
    */
  def isWiredBackend(a: Agent[?]): Boolean = all.exists(_.sharesBackendWith(a))

private[orca] object WiredAgents:

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

  /** Closes `agents` when the enclosing scope ends. */
  def closeAfterScope(agents: List[Agent[?]])(using ResourceScope): Unit =
    releaseAfterScope(agents.foreach(_.close()))
