package orca.tools.codex

import orca.{
  Announce,
  Backend,
  CodexTool,
  Interaction,
  JsonData,
  LlmBackend,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  Prompts,
  SessionId
}
import orca.io.DefaultLlmCall

/** Default [[CodexTool]] implementation that routes calls through a
  * [[LlmBackend]][Backend.Codex.type]]. Mirrors `DefaultClaudeTool` — the tool
  * is immutable; `withConfig`, `withSystemPrompt`, and `mini` return new
  * instances so flow scripts can chain without side effects.
  */
class DefaultCodexTool(
    backend: LlmBackend[Backend.Codex.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    emit: OrcaEvent => Unit,
    interaction: Interaction,
    val name: String = "codex"
) extends CodexTool:

  /** Pin the cheap-and-fast model variant. The literal model id matches what's
    * available in the installed `codex-cli` (gpt-5.4-mini in 0.125.0); newer
    * codex versions may rename, in which case callers override via
    * `withConfig(LlmConfig(model = Some("..."))`.
    */
  def mini: CodexTool = withModel("gpt-5.4-mini")

  def withConfig(newConfig: LlmConfig): CodexTool =
    copy(config = newConfig)

  def withSystemPrompt(prompt: String): CodexTool =
    copy(config = config.copy(systemPrompt = Some(prompt)))

  def withName(newName: String): CodexTool = copy(name = newName)

  def ask(prompt: String, callConfig: LlmConfig = LlmConfig.default): String =
    val effective = effectiveConfig(callConfig)
    val result = backend.runHeadless(prompt, effective, workDir)
    emitTokens(effective, result)
    result.output

  def startSession(
      prompt: String,
      callConfig: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.Codex.type], String) =
    val effective = effectiveConfig(callConfig)
    val result = backend.runHeadless(prompt, effective, workDir)
    emitTokens(effective, result)
    (result.sessionId, result.output)

  def continueSession(
      sessionId: SessionId[Backend.Codex.type],
      prompt: String,
      callConfig: LlmConfig = LlmConfig.default
  ): String =
    val effective = effectiveConfig(callConfig)
    val result = backend.continueHeadless(sessionId, prompt, effective, workDir)
    emitTokens(effective, result)
    result.output

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.Codex.type, O] =
    new DefaultLlmCall[Backend.Codex.type, O](
      backend,
      effectiveConfig,
      prompts,
      workDir,
      emit,
      interaction,
      defaultModel = name
    )

  private def withModel(model: String): CodexTool =
    copy(config = config.copy(model = Some(model)))

  private def copy(
      backend: LlmBackend[Backend.Codex.type] = backend,
      config: LlmConfig = config,
      prompts: Prompts = prompts,
      workDir: os.Path = workDir,
      emit: OrcaEvent => Unit = emit,
      interaction: Interaction = interaction,
      name: String = name
  ): DefaultCodexTool =
    new DefaultCodexTool(
      backend,
      config,
      prompts,
      workDir,
      emit,
      interaction,
      name
    )

  private def effectiveConfig(callConfig: LlmConfig): LlmConfig =
    if callConfig eq LlmConfig.default then config else callConfig

  private def emitTokens(
      effective: LlmConfig,
      result: orca.LlmResult[Backend.Codex.type]
  ): Unit =
    val bucket = result.model.orElse(effective.model).getOrElse(name)
    emit(OrcaEvent.TokensUsed(bucket, result.usage))
