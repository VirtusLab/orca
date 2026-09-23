package orca.agents

import orca.backend.{AgentBackend, AgentResult, Interaction}
import orca.events.{OrcaEvent, OrcaListener}
import ox.tap

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
    * than the trait's `None` default. `dispatchFor` / `resumeWireId` /
    * `rehydrateResumeWireId` on [[Agent]] route through this.
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

  /** Latches the shared backend closed, so every handle sharing it refuses to
    * run.
    */
  override private[orca] def close(): Unit = backend.markClosed()

  private[orca] val autonomous: AutonomousTextCall[B] =
    new AutonomousTextCall[B]:
      private[orca] def runWithSession(
          prompt: String,
          session: SessionId[B],
          sessionKey: Option[SessionKey],
          emitPrompt: Boolean
      )(using orca.InStage): String =
        backend.checkNotClosed()
        if emitPrompt then events.onEvent(OrcaEvent.UserPrompt(prompt))
        val accounting = turnAccounting(session, sessionKey)
        val result =
          textTurn(
            prompt,
            session,
            accounting,
            OrcaListener.attributedTo(events, name)
          )
        accounting.sessionCommitted()
        result.output

  /** See [[Agent.quietTextTurn]]: the turn runs against a filtered event sink
    * that drops the streaming display events (`AssistantMessage`, `ToolUse`)
    * while everything else the drain emits (`Error`, auto-denial notices) still
    * reaches the real listener, as does `TokensUsed`.
    */
  override private[orca] def quietTextTurn(prompt: String)(using
      orca.InStage
  ): String =
    val attributed = OrcaListener.attributedTo(events, name)
    val quietEvents: OrcaListener = (e: OrcaEvent) =>
      e match
        case _: OrcaEvent.AssistantMessage | _: OrcaEvent.ToolUse => ()
        case other => attributed.onEvent(other)
    val session = SessionId.fresh[B]
    textTurn(
      prompt,
      session,
      turnAccounting(session, sessionKey = None),
      quietEvents
    ).output

  /** One free-form turn, with its spend reported through `accounting` whether
    * it succeeds or fails after the model ran.
    */
  private def textTurn(
      prompt: String,
      session: SessionId[B],
      accounting: TurnAccounting[B],
      listener: OrcaListener
  ): AgentResult[B] =
    accounting
      .recording(backend.runAutonomous(prompt, session, config, listener))
      .tap(accounting.succeeded(_, TurnAccounting.OnlyTurn))

  def resultAs[O: JsonData: Announce]: AgentCall[B, O] =
    backend.checkNotClosed()
    new DefaultAgentCall[B, O](
      backend,
      config,
      prompts,
      events,
      interaction,
      agentName = name,
      agentRole = role
    )

  private def turnAccounting(
      session: SessionId[B],
      sessionKey: Option[SessionKey]
  ): TurnAccounting[B] =
    new TurnAccounting[B](
      events = events,
      agentName = name,
      role = role,
      backend = backend,
      session = session,
      sessionKey = sessionKey,
      pinned = config.model
    )
