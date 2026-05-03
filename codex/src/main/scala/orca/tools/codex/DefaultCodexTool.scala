package orca.tools.codex

import orca.{
  Announce,
  AutonomousTextCall,
  Backend,
  CodexTool,
  Interaction,
  JsonData,
  LlmBackend,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  OrcaListener,
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
    events: OrcaListener,
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

  val autonomous: AutonomousTextCall[Backend.Codex.type] =
    new AutonomousTextCall[Backend.Codex.type]:
      def run(
          prompt: String,
          callConfig: LlmConfig = LlmConfig.default
      ): String =
        runHeadless(prompt, callConfig, resume = None).output

      def startSession(
          prompt: String,
          callConfig: LlmConfig = LlmConfig.default
      ): (SessionId[Backend.Codex.type], String) =
        val result = runHeadless(prompt, callConfig, resume = None)
        (result.sessionId, result.output)

      def continueSession(
          sessionId: SessionId[Backend.Codex.type],
          prompt: String,
          callConfig: LlmConfig = LlmConfig.default
      ): String =
        runHeadless(prompt, callConfig, resume = Some(sessionId)).output

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.Codex.type, O] =
    new DefaultLlmCall[Backend.Codex.type, O](
      backend,
      effectiveConfig,
      prompts,
      workDir,
      events,
      interaction,
      agentName = name
    )

  private def withModel(model: String): CodexTool =
    copy(config = config.copy(model = Some(model)))

  private def copy(
      backend: LlmBackend[Backend.Codex.type] = backend,
      config: LlmConfig = config,
      prompts: Prompts = prompts,
      workDir: os.Path = workDir,
      events: OrcaListener = events,
      interaction: Interaction = interaction,
      name: String = name
  ): DefaultCodexTool =
    new DefaultCodexTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )

  private def effectiveConfig(callConfig: LlmConfig): LlmConfig =
    if callConfig eq LlmConfig.default then config else callConfig

  private def runHeadless(
      prompt: String,
      callConfig: LlmConfig,
      resume: Option[SessionId[Backend.Codex.type]]
  ): orca.LlmResult[Backend.Codex.type] =
    val effective = effectiveConfig(callConfig)
    val result = resume match
      case Some(sid) =>
        backend.continueHeadless(sid, prompt, effective, workDir)
      case None => backend.runHeadless(prompt, effective, workDir)
    emitTokens(effective, result)
    result

  private def emitTokens(
      effective: LlmConfig,
      result: orca.LlmResult[Backend.Codex.type]
  ): Unit =
    val model = result.model.orElse(effective.model)
    events.onEvent(OrcaEvent.TokensUsed(name, model, result.usage))
