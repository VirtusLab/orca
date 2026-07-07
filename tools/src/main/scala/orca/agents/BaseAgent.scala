package orca.agents

import orca.OrcaFlowException
import orca.backend.{Interaction, AgentBackend, AgentResult}
import orca.events.{OrcaEvent, OrcaListener}

/** Skeleton shared by Claude and Codex's default tools — and by any future
  * backend that follows the same `AgentBackend` contract. Centralises the
  * autonomous-text path (which is otherwise pure delegation to
  * `backend.runAutonomous` plus `TokensUsed` emission), the `resultAs[O]`
  * factory, and the `withConfig` / `withSystemPrompt` / `withName` builders.
  *
  * Concrete subclasses provide:
  *   - the `Self` type bound (their own `Agent` subtype) so the builders return
  *     the concrete type;
  *   - a `copyTool` factory that knows the subclass-specific extra params (e.g.
  *     claude has no extra params; a hypothetical backend that needed more
  *     config would override `copyTool` to thread them through);
  *   - the model accessors (`haiku`/`sonnet`/`opus`, `mini`, …) — these are
  *     backend-specific and stay on the subclass.
  */
abstract class BaseAgent[B <: BackendTag, Self <: Agent[B]](
    backend: AgentBackend[B],
    config: AgentConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction
) extends Agent[B]:

  /** Build a sibling instance with the supplied overrides. Concrete subclasses
    * call their own constructor with subclass-specific extra parameters
    * preserved. Used by `withConfig`, `withSystemPrompt`, `withName`, and the
    * model-pinning accessors.
    */
  protected def copyTool(
      config: AgentConfig = config,
      name: String = name
  ): Self

  def withConfig(newConfig: AgentConfig): Self = copyTool(config = newConfig)
  def withSystemPrompt(prompt: String): Self =
    copyTool(config = config.copy(systemPrompt = Some(prompt)))
  def withName(newName: String): Self = copyTool(name = newName)
  def withTools(tools: ToolSet): Self =
    copyTool(config = config.copy(tools = tools))
  override def withReadOnly: Self = withTools(ToolSet.ReadOnly)
  override def withNetworkOnly: Self = withTools(ToolSet.NetworkOnly)
  override def withSelfManagedGit: Self =
    copyTool(config = config.copy(selfManagedGit = true))

  /** Pin the underlying CLI's `--model` flag for subsequent calls. Public so
    * each backend trait can surface it (`claude.withModel(...)`,
    * `codex.withModel(...)`); the named accessors (`haiku`/`sonnet`/`opus`,
    * `mini`) are conveniences over it. Returns `Self` so they keep the concrete
    * type.
    */
  def withModel(model: Model): Self =
    copyTool(config = config.copy(model = Some(model)))

  /** The cheap variant: a `withCheapModel` override if the caller pinned one,
    * otherwise the backend's built-in [[defaultCheap]] tier.
    */
  override def cheap: Agent[B] =
    config.cheapModel.map(withModel).getOrElse(defaultCheap)

  override def withCheapModel(model: Model): Self =
    copyTool(config = config.copy(cheapModel = Some(model)))

  /** Exposes the backend's whole session-durability capability, so a tool built
    * on a real [[orca.backend.AgentBackend]] reflects actual session state
    * rather than the trait's `None` default. The `final` `willContinue` /
    * `resumeWireId` / `registerResumeWireId` on [[Agent]] route through this.
    */
  override private[orca] def sessionSupport
      : Option[orca.backend.SessionSupport[B]] =
    Some(backend.sessions)

  /** Delegates to the backend's runtime tag — see [[Agent.backendTag]].
    */
  override private[orca] def backendTag: Option[BackendTag] = Some(backend.tag)

  /** Gates [[autonomous]]`.run` and [[resultAs]]'s gateway construction on the
    * backend's closed latch (Epic 7.5). A leaked agent handle used after its
    * flow ended would otherwise silently emit to a closed run's dispatcher —
    * loud on opencode (a dead `serve` process), invisible on claude/codex. The
    * latch lives on the shared `backend`, not this instance, so every
    * `copyTool`-derived sibling (`leaked.opus`, `leaked.withConfig(...)`, …) is
    * covered too — see [[orca.backend.AgentBackend.markClosed]].
    */
  private def checkNotClosed(): Unit =
    if backend.isClosed then
      throw new OrcaFlowException(
        "agent used after its flow ended — agents are scoped to the flow(...) that created them"
      )

  /** Latches the shared backend closed first (so a `run`/`resultAs` call racing
    * this close observes one consistent state or the other, never a
    * live-looking agent whose backend has already been torn down), then
    * delegates resource teardown — see [[Agent.close]].
    */
  override private[orca] def close(): Unit =
    backend.markClosed()
    backend.close()

  val autonomous: AutonomousTextCall[B] = new AutonomousTextCall[B]:
    def run(
        prompt: String,
        session: SessionId[B] = SessionId.fresh[B],
        callConfig: Option[AgentConfig] = None,
        emitPrompt: Boolean = true
    )(using orca.InStage): (SessionId[B], String) =
      checkNotClosed()
      val effective = effectiveConfig(callConfig)
      if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(prompt))
      val result =
        backend.runAutonomous(prompt, session, effective, workDir, events)
      emitTokens(effective, result)
      // Return the caller-supplied client handle; result.wireId is the
      // wire-side truth, learned by the registry, not a caller handle.
      (session, result.output)

  def resultAs[O: JsonData: Announce]: AgentCall[B, O] =
    checkNotClosed()
    new DefaultAgentCall[B, O](
      backend,
      effectiveConfig,
      prompts,
      workDir,
      events,
      interaction,
      agentName = name
    )

  /** `agent` axis is always this tool's name; `model` prefers the
    * response-reported model (most precise) and falls back to whatever the
    * caller pinned in config. Stays None when neither is known.
    */
  private def emitTokens(effective: AgentConfig, result: AgentResult[B]): Unit =
    val model = result.model.orElse(effective.model)
    events.onEvent(OrcaEvent.TokensUsed(name, model, result.usage))

  /** `None` (the caller omitted the per-call `config` arg) falls back to the
    * tool-level config. An explicit `Some(...)` from the call site wholly
    * replaces the tool-level one — there is no per-field merge.
    */
  private def effectiveConfig(callConfig: Option[AgentConfig]): AgentConfig =
    callConfig.getOrElse(config)
