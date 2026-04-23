package orca.claude

import orca.{
  AgentInput,
  Backend,
  LlmBackend,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  PromptTemplate,
  SessionId
}
import orca.io.{JsonSchemaGen, ResponseParser}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import ox.resilience.retry
import sttp.tapir.Schema

/** Default implementation of LlmCall for the Claude backend. Only the headless
  * `prompt` path is wired today — session and interactive variants are stubbed
  * pending Epic 8 integration.
  *
  * `prompt` retries the LLM call using `config.retrySchedule`. On a JSON parse
  * failure the next attempt uses the corrective retry prompt (carrying the
  * failed response and the parser error), counting against the same retry
  * budget as transport failures.
  */
private case class FailedAttempt(response: String, parserError: String)

class DefaultLlmCall[O](
    backend: LlmBackend[Backend.ClaudeCode.type],
    effectiveConfig: LlmConfig => LlmConfig,
    template: PromptTemplate,
    workDir: os.Path,
    emit: OrcaEvent => Unit
)(using schema: Schema[O], codec: ConfiguredJsonValueCodec[O])
    extends LlmCall[Backend.ClaudeCode.type, O]:

  def autonomous[I](input: I, config: LlmConfig = LlmConfig.default)(using
      ai: AgentInput[I]
  ): O =
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
      val result = backend.runHeadless(promptText, effective, workDir)
      emit(OrcaEvent.TokensUsed(result.usage))
      try ResponseParser.parse[O](result.output)
      catch
        case e: JsonReaderException =>
          lastFailure = Some(
            FailedAttempt(
              response = result.output,
              parserError =
                Option(e.getMessage).getOrElse("unknown parse error")
            )
          )
          throw e

  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) = ???

  def continueSession[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = ???

  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) = ???

  def continueInteractive[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = ???
