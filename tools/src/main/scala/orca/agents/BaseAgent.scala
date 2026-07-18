package orca.agents

import orca.OrcaFlowException
import orca.backend.{Interaction, AgentBackend, AgentResult}
import orca.events.{OrcaEvent, OrcaListener}

/** Skeleton shared by all backends' default tools. Centralises the
  * autonomous-text path (delegation to `backend.runAutonomous` plus
  * `TokensUsed` emission), the `resultAs[O]` factory, and the `withConfig` /
  * `withSystemPrompt` / `withName` builders.
  *
  * Concrete subclasses provide:
  *   - the `Self` type bound (their own `Agent` subtype) so the builders return
  *     the concrete type;
  *   - a `copyTool` factory threading through subclass-specific extra params;
  *   - the backend-specific model accessors (`haiku`/`sonnet`/`opus`, `mini`).
  */
abstract class BaseAgent[B <: BackendTag, Self <: Agent[B]](
    backend: AgentBackend[B],
    config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction
) extends Agent[B]:

  /** Build a sibling instance with the supplied overrides, preserving
    * subclass-specific extra parameters. Used by `withConfig`,
    * `withSystemPrompt`, `withName`, and the model-pinning accessors.
    */
  protected def copyTool(
      config: AgentConfig = config,
      name: String = name,
      role: Option[String] = role
  ): Self

  def withConfig(newConfig: AgentConfig): Self = copyTool(config = newConfig)
  def withSystemPrompt(prompt: String): Self =
    copyTool(config = config.copy(systemPrompt = Some(prompt)))
  def withName(newName: String): Self = copyTool(name = newName)
  override def withRole(newRole: String): Self = copyTool(role = Some(newRole))
  def withTools(tools: ToolSet): Self =
    copyTool(config = config.copy(tools = tools))
  override def withReadOnly: Self = withTools(ToolSet.ReadOnly)
  override def withNetworkOnly: Self = withTools(ToolSet.NetworkOnly)
  override def withSelfManagedGit: Self =
    copyTool(config = config.copy(selfManagedGit = true))

  /** Pin the underlying CLI's `--model` flag for subsequent calls. Public so
    * each backend trait can surface it; the named accessors
    * (`haiku`/`sonnet`/`opus`, `mini`) are conveniences over it.
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

  /** Exposes the backend's session-durability capability, so a tool built on a
    * real [[orca.backend.AgentBackend]] reflects actual session state rather
    * than the trait's `None` default. `willContinue` / `resumeWireId` /
    * `registerResumeWireId` on [[Agent]] route through this.
    */
  override private[orca] def sessionSupport
      : Option[orca.backend.SessionSupport[B]] =
    Some(backend.sessions)

  override private[orca] def backendTag: Option[BackendTag] = Some(backend.tag)

  override private[orca] def backendIdentity: Option[AnyRef] = Some(
    backend.closedFlag
  )

  /** Gates [[autonomous]]`.run` and [[resultAs]]'s gateway construction on the
    * backend's closed latch, so a leaked agent handle used after its flow ended
    * can't silently emit to a closed run's dispatcher. The latch lives on the
    * shared `backend`, so every `copyTool`-derived sibling is covered too.
    */
  private def checkNotClosed(): Unit =
    if backend.isClosed then
      throw new OrcaFlowException(AgentBackend.ClosedMessage)

  /** Latches the shared backend closed first (so a `run`/`resultAs` call racing
    * this close never sees a live-looking agent whose backend is torn down),
    * then delegates resource teardown.
    */
  override private[orca] def close(): Unit =
    backend.markClosed()
    backend.close()

  private[orca] val autonomous: AutonomousTextCall[B] =
    new AutonomousTextCall[B]:
      private[orca] def runWithSession(
          prompt: String,
          session: SessionId[B],
          callConfig: Option[AgentConfig],
          emitPrompt: Boolean
      )(using orca.InStage): String =
        checkNotClosed()
        val effective = effectiveConfig(callConfig)
        if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(prompt))
        val result =
          backend.runAutonomous(prompt, session, effective, events)
        emitTokens(effective, result)
        result.output

  /** See [[Agent.quietTextTurn]]: the turn runs against a filtered event sink
    * that drops the streaming display events (`AssistantMessage`, `ToolUse`)
    * while everything else the drain emits (`Error`, auto-denial notices) still
    * reaches the real listener, as does `TokensUsed` below.
    */
  override private[orca] def quietTextTurn(prompt: String)(using
      orca.InStage
  ): String =
    checkNotClosed()
    val effective = effectiveConfig(None)
    val quietEvents: OrcaListener = (e: OrcaEvent) =>
      e match
        case _: OrcaEvent.AssistantMessage | _: OrcaEvent.ToolUse => ()
        case other => events.onEvent(other)
    val result =
      backend.runAutonomous(prompt, SessionId.fresh[B], effective, quietEvents)
    emitTokens(effective, result)
    result.output

  def resultAs[O: JsonData: Announce]: AgentCall[B, O] =
    checkNotClosed()
    new DefaultAgentCall[B, O](
      backend,
      effectiveConfig,
      prompts,
      events,
      interaction,
      agentName = name,
      agentRole = role
    )

  /** `model` prefers the response-reported model (most precise), falling back
    * to whatever the caller pinned in config, `None` when neither is known.
    */
  private def emitTokens(effective: AgentConfig, result: AgentResult[B]): Unit =
    val model = result.model.orElse(effective.model)
    events.onEvent(OrcaEvent.TokensUsed(name, model, result.usage, role))

  /** `None` (the caller omitted the per-call `config` arg) falls back to the
    * tool-level config. An explicit `Some(...)` from the call site wholly
    * replaces the tool-level one — there is no per-field merge.
    */
  private def effectiveConfig(callConfig: Option[AgentConfig]): AgentConfig =
    callConfig.getOrElse(config)
