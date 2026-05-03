package orca.io

import orca.{
  AgentInput,
  Announce,
  Backend,
  Interaction,
  JsonData,
  LlmBackend,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  Prompts,
  SessionId
}
import ox.resilience.retry

/** Default implementation of [[LlmCall]] for any backend.
  *
  * All structured headless calls share a retry-with-corrective-prompt loop:
  * when the response fails to parse as `O`, the next attempt's prompt includes
  * the failed output and the parser error so the model can self-correct. Only
  * the final successful attempt's session id is returned. Headless variants
  * (`autonomous`, `startSession`, `continueSession`) go through
  * `backend.runHeadless` / `backend.continueHeadless`; interactive variants
  * open a [[orca.Conversation]] via the backend and hand it to the supplied
  * [[Interaction]] for rendering and user steering.
  */
private case class FailedAttempt(response: String, parserError: String)

class DefaultLlmCall[B <: Backend, O](
    backend: LlmBackend[B],
    effectiveConfig: LlmConfig => LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    emit: OrcaEvent => Unit,
    interaction: Interaction,
    /** Used as the model identifier on `OrcaEvent.TokensUsed` when
      * `LlmConfig.model` isn't pinned — typically the owning `LlmTool.name`
      * ("claude", "codex"). Always a real string so `CostTracker` doesn't have
      * to deal with an `<unknown>` bucket.
      */
    defaultModel: String
)(using jd: JsonData[O], announce: Announce[O])
    extends LlmCall[B, O]:

  private given sttp.tapir.Schema[O] = jd.schema
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[O] =
    jd.codec

  /** Emit a `StructuredResult` event carrying the raw payload and the
    * `Announce[O]`-derived summary (if any). The terminal listener renders
    * `summary` when present and skips otherwise; non-terminal listeners (Slack,
    * structured logs) can carry `raw` through unchanged.
    */
  private def emitStructuredResult(raw: String, value: O): Unit =
    emit(OrcaEvent.StructuredResult(raw, announce.message(value)))

  def autonomous[I](input: I, config: LlmConfig = LlmConfig.default)(using
      ai: AgentInput[I]
  ): O = runHeadlessWithRetry(input, config, resume = None)._2

  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O) =
    runHeadlessWithRetry(input, config, resume = None)

  /** Continue the given session with a structured input. If the response fails
    * to parse as `O`, each retry resumes the same session with the corrective
    * prompt, which means the transcript a later `continueSession` sees will
    * include those stale corrective turns. That's the price of self-correction
    * against a persistent session.
    */
  def continueSession[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = runHeadlessWithRetry(input, config, resume = Some(sessionId))._2

  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O) = runInteractiveOnce(input, config, resume = None)

  def continueInteractive[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = runInteractiveOnce(input, config, resume = Some(sessionId))._2

  /** Headless retry loop used by autonomous/startSession/continueSession. On a
    * parse failure the next attempt swaps the original prompt for a corrective
    * one; the returned session id is whichever one succeeded.
    */
  private def runHeadlessWithRetry[I](
      input: I,
      config: LlmConfig,
      resume: Option[SessionId[B]]
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val initialPrompt = prompts.autonomous(serialized, outputSchema, config)
    val effective = effectiveConfig(config)

    // Threaded across retry attempts via closure so a parse failure can
    // steer the next attempt with the corrective prompt. Method-scope var
    // allowed by the project's FP conventions.
    var lastFailure: Option[FailedAttempt] = None

    retry(effective.retrySchedule):
      val promptText = lastFailure match
        case Some(f) => prompts.retry(f.response, f.parserError)
        case None    => initialPrompt
      val result = resume match
        case Some(sid) =>
          backend.continueHeadless(sid, promptText, effective, workDir)
        case None => backend.runHeadless(promptText, effective, workDir)
      emit(
        OrcaEvent.TokensUsed(
          result.model.orElse(effective.model).getOrElse(defaultModel),
          result.usage
        )
      )
      try
        val parsed = ResponseParser.parse[O](result.output)
        emitStructuredResult(result.output, parsed)
        (result.sessionId, parsed)
      catch
        case e: MalformedAgentOutputException =>
          lastFailure = Some(
            FailedAttempt(
              response = e.rawOutput,
              parserError = e.shortCause
            )
          )
          throw e

  /** Interactive variant. No retry: the user is steering the session and a
    * parse failure here means the session's final payload didn't match the
    * expected schema — surface it directly so the flow sees it rather than
    * silently relaunching the agent.
    */
  private def runInteractiveOnce[I](
      input: I,
      config: LlmConfig,
      resume: Option[SessionId[B]]
  )(using ai: AgentInput[I]): (SessionId[B], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val prompt = prompts.interactive(serialized, outputSchema, config)
    val effective = effectiveConfig(config)
    val conversation = resume match
      case Some(sid) =>
        backend.continueInteractive(
          sid,
          prompt,
          displayPrompt = serialized,
          effective,
          workDir,
          Some(outputSchema)
        )
      case None =>
        backend.runInteractive(
          prompt,
          displayPrompt = serialized,
          effective,
          workDir,
          Some(outputSchema)
        )
    val result = interaction.drive(conversation)
    // TokensUsed emits on the normal path only. If the user cancels
    // mid-session, drive throws before this line — and the wire
    // protocols don't always carry partial usage, so there's nothing
    // authoritative to emit at cancel time.
    emit(
      OrcaEvent.TokensUsed(
        result.model.orElse(effective.model).getOrElse(defaultModel),
        result.usage
      )
    )
    val parsed = ResponseParser.parse[O](result.output)
    emitStructuredResult(result.output, parsed)
    (result.sessionId, parsed)
