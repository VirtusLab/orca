package orca.tools.claude

import orca.{
  AgentInput,
  Backend,
  Interaction,
  JsonData,
  LlmBackend,
  LlmCall,
  LlmConfig,
  LlmResult,
  OrcaEvent,
  PromptTemplate,
  SessionId
}
import orca.io.{JsonSchemaGen, ResponseParser}
import orca.io.MalformedAgentOutputException
import ox.resilience.retry

/** Default implementation of LlmCall for the Claude backend.
  *
  * All structured headless calls share a retry-with-corrective-prompt
  * loop: when the response fails to parse as `O`, the next attempt's
  * prompt includes the failed output and the parser error so the model
  * can self-correct. Only the final successful attempt's session id is
  * returned. Headless variants (`autonomous`, `startSession`,
  * `continueSession`) go through `backend.runHeadless` /
  * `backend.continueHeadless`; interactive variants open a
  * stream-json `Conversation` via the backend and hand it to the
  * supplied `Interaction` for rendering and user steering.
  */
private case class FailedAttempt(response: String, parserError: String)

class DefaultLlmCall[O](
    backend: LlmBackend[Backend.ClaudeCode.type],
    effectiveConfig: LlmConfig => LlmConfig,
    template: PromptTemplate,
    workDir: os.Path,
    emit: OrcaEvent => Unit,
    interaction: Interaction
)(using jd: JsonData[O])
    extends LlmCall[Backend.ClaudeCode.type, O]:

  private given sttp.tapir.Schema[O] = jd.schema
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[O] =
    jd.codec

  def autonomous[I](input: I, config: LlmConfig = LlmConfig.default)(using
      ai: AgentInput[I]
  ): O = runHeadlessWithRetry(input, config, resume = None)._2

  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) =
    runHeadlessWithRetry(input, config, resume = None)

  /** Continue the given session with a structured input. If the response
    * fails to parse as `O`, each retry calls `--resume <sessionId>` with
    * the corrective prompt, which means the transcript that a later
    * `continueSession` sees will include those stale corrective turns.
    * That's the price of self-correction against a persistent session.
    */
  def continueSession[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = runHeadlessWithRetry(input, config, resume = Some(sessionId))._2

  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) =
    runInteractiveOnce(input, config, resume = None)

  def continueInteractive[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O =
    runInteractiveOnce(input, config, resume = Some(sessionId))._2

  /** Headless retry loop used by autonomous/startSession/continueSession.
    * On a parse failure the next attempt swaps the original prompt for a
    * corrective one; the returned session id is whichever one succeeded.
    */
  private def runHeadlessWithRetry[I](
      input: I,
      config: LlmConfig,
      resume: Option[SessionId[Backend.ClaudeCode.type]]
  )(using ai: AgentInput[I]): (SessionId[Backend.ClaudeCode.type], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val initialPrompt = template.autonomous(serialized, outputSchema, config)
    val effective = effectiveConfig(config)

    // Threaded across retry attempts via closure so a parse failure can
    // steer the next attempt with the corrective prompt. Method-scope var
    // allowed by the project's FP conventions.
    var lastFailure: Option[FailedAttempt] = None

    retry(effective.retrySchedule):
      val promptText = lastFailure match
        case Some(f) => template.retry(f.response, f.parserError)
        case None    => initialPrompt
      val result = resume match
        case Some(sid) =>
          backend.continueHeadless(sid, promptText, effective, workDir)
        case None => backend.runHeadless(promptText, effective, workDir)
      emit(OrcaEvent.TokensUsed(effective.model, result.usage))
      try (result.sessionId, ResponseParser.parse[O](result.output))
      catch
        case e: MalformedAgentOutputException =>
          lastFailure = Some(
            FailedAttempt(
              response = e.rawOutput,
              parserError = e.shortCause
            )
          )
          throw e

  /** Interactive variant. No retry: the user is steering the session and
    * a parse failure here means the session's final payload didn't match
    * the expected schema — surface the JsonReaderException directly so
    * the flow sees it rather than silently re-launching claude.
    */
  private def runInteractiveOnce[I](
      input: I,
      config: LlmConfig,
      resume: Option[SessionId[Backend.ClaudeCode.type]]
  )(using ai: AgentInput[I]): (SessionId[Backend.ClaudeCode.type], O) =
    val serialized = ai.serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val prompt = template.interactive(serialized, outputSchema, config)
    val effective = effectiveConfig(config)
    val conversation = resume match
      case Some(sid) =>
        backend.continueInteractive(
          sid,
          prompt,
          effective,
          workDir,
          Some(outputSchema)
        )
      case None =>
        backend.runInteractive(
          prompt,
          effective,
          workDir,
          Some(outputSchema)
        )
    val result = interaction.drive(conversation)
    // TokensUsed emits on the normal path only. If the user cancels
    // mid-session, drive throws before this line — and the stream-json
    // protocol doesn't carry partial usage today, so there's nothing
    // authoritative to emit at cancel time. Revisit when upstream adds
    // running-usage messages.
    emit(OrcaEvent.TokensUsed(effective.model, result.usage))
    (result.sessionId, ResponseParser.parse[O](result.output))
