package orca.agents

import orca.backend.{Interaction, AgentBackend}
import orca.events.{OrcaEvent, OrcaListener}

/** Skeleton shared by all backends' default tools. Centralises the
  * autonomous-text path (delegation to `backend.runAutonomous`, with
  * [[TurnAccounting]] emitting), the `resultAs[O]` factory, and the
  * `withConfig` / `withSystemPrompt` / `withName` builders.
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

  override private[orca] def emitEvent(event: OrcaEvent): Unit =
    events.onEvent(event)

  override private[orca] def configuredModel: Option[Model] = config.model

  override private[orca] def backendIdentity: Option[AnyRef] = Some(
    backend.closedFlag
  )

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
          sessionName: Option[String],
          callConfig: Option[AgentConfig],
          emitPrompt: Boolean
      )(using orca.InStage): String =
        backend.checkNotClosed()
        val effective = effectiveConfig(callConfig)
        backend.announceEnforcementShortfall(effective, session, events)
        if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(prompt))
        val accounting = turnAccounting(effective, session, sessionName)
        val result = accounting.recording:
          backend.runAutonomous(prompt, session, effective, events)
        accounting.succeeded(result, TurnAccounting.OnlyTurn)
        accounting.sessionCommitted()
        result.output

  /** See [[Agent.quietTextTurn]]: the turn runs against a filtered event sink
    * that drops the streaming display events (`AssistantMessage`, `ToolUse`)
    * while everything else the drain emits (`Error`, auto-denial notices) still
    * reaches the real listener, as does `TokensUsed` below.
    */
  override private[orca] def quietTextTurn(prompt: String)(using
      orca.InStage
  ): String =
    backend.checkNotClosed()
    val effective = effectiveConfig(None)
    val quietEvents: OrcaListener = (e: OrcaEvent) =>
      e match
        case _: OrcaEvent.AssistantMessage | _: OrcaEvent.ToolUse => ()
        case other => events.onEvent(other)
    val session = SessionId.fresh[B]
    backend.announceEnforcementShortfall(effective, session, events)
    val accounting = turnAccounting(effective, session, sessionName = None)
    val result = accounting.recording:
      backend.runAutonomous(prompt, session, effective, quietEvents)
    accounting.succeeded(result, TurnAccounting.OnlyTurn)
    result.output

  def resultAs[O: JsonData: Announce]: AgentCall[B, O] =
    backend.checkNotClosed()
    new DefaultAgentCall[B, O](
      backend,
      effectiveConfig,
      prompts,
      events,
      interaction,
      agentName = name,
      agentRole = role
    )

  private def turnAccounting(
      effective: AgentConfig,
      session: SessionId[B],
      sessionName: Option[String]
  ): TurnAccounting[B] =
    new TurnAccounting[B](
      events = events,
      agentName = name,
      role = role,
      backend = backend,
      session = session,
      sessionName = sessionName,
      pinned = effective.model
    )

  /** `None` (the caller omitted the per-call `config` arg) falls back to the
    * tool-level config. An explicit `Some(...)` from the call site wholly
    * replaces the tool-level one — there is no per-field merge.
    */
  private def effectiveConfig(callConfig: Option[AgentConfig]): AgentConfig =
    callConfig.getOrElse(config)
