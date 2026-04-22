package orca.cli

import _root_.orca.{
  Backend,
  ClaudeTool,
  LlmBackend,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  PromptTemplate
}
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

/** Default ClaudeTool implementation that routes calls through a
  * `LlmBackend[Backend.ClaudeCode.type]`. The tool is immutable; `withConfig`,
  * `withSystemPrompt`, and the model accessors return new instances so flow
  * scripts can chain without side effects.
  *
  * `ask` and `result[O].prompt` are implemented against the headless backend
  * path; session and interactive variants remain stubbed and will land with
  * Epic 8 / Epic 4 follow-ups once they're exercised end-to-end.
  */
class DefaultClaudeTool(
    backend: LlmBackend[Backend.ClaudeCode.type],
    config: LlmConfig,
    template: PromptTemplate,
    workDir: os.Path,
    emit: OrcaEvent => Unit
) extends ClaudeTool:

  val name: String = "claude"

  def haiku: ClaudeTool = withModel("claude-haiku-4-5")
  def sonnet: ClaudeTool = withModel("claude-sonnet-4-6")
  def opus: ClaudeTool = withModel("claude-opus-4-7")

  def withConfig(newConfig: LlmConfig): ClaudeTool =
    copy(config = newConfig)

  def withSystemPrompt(prompt: String): ClaudeTool =
    copy(config = config.copy(systemPrompt = Some(prompt)))

  def ask(prompt: String, callConfig: LlmConfig = LlmConfig.default): String =
    val effective = effectiveConfig(callConfig)
    val result = backend.runHeadless(prompt, effective, workDir)
    emit(OrcaEvent.TokensUsed(result.usage))
    result.output

  def result[O: Schema: ConfiguredJsonValueCodec]
      : LlmCall[Backend.ClaudeCode.type, O] =
    new DefaultLlmCall[O](backend, effectiveConfig, template, workDir, emit)

  private def withModel(model: String): ClaudeTool =
    copy(config = config.copy(model = Some(model)))

  private def copy(
      backend: LlmBackend[Backend.ClaudeCode.type] = backend,
      config: LlmConfig = config,
      template: PromptTemplate = template,
      workDir: os.Path = workDir,
      emit: OrcaEvent => Unit = emit
  ): DefaultClaudeTool =
    new DefaultClaudeTool(backend, config, template, workDir, emit)

  private def effectiveConfig(callConfig: LlmConfig): LlmConfig =
    // Call-level config overrides tool-level values where the call explicitly
    // set them. Detection is reference-based: a caller who omitted the arg
    // receives the shared LlmConfig.default singleton.
    if callConfig eq LlmConfig.default then config else callConfig
