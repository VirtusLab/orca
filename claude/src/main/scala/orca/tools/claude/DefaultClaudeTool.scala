package orca.tools.claude

import orca.{
  Announce,
  AutonomousTextCall,
  Backend,
  ClaudeTool,
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

/** Default ClaudeTool implementation that routes calls through a
  * `LlmBackend[Backend.ClaudeCode.type]`. The tool is immutable; `withConfig`,
  * `withSystemPrompt`, and the model accessors return new instances so flow
  * scripts can chain without side effects.
  *
  * Free-form text calls (`autonomous.run` / `startSession` / `continueSession`)
  * and the structured-output `resultAs[O].autonomous.*` shape go through the
  * backend's headless mode. Interactive structured calls (`resultAs[O]
  * .interactive.startSession`) spawn claude in stream-json mode and wrap the
  * subprocess in a `Conversation` that the supplied `interaction` drives to
  * completion.
  */
class DefaultClaudeTool(
    backend: LlmBackend[Backend.ClaudeCode.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "claude"
) extends ClaudeTool:

  def haiku: ClaudeTool = withModel("claude-haiku-4-5")
  def sonnet: ClaudeTool = withModel("claude-sonnet-4-6")
  def opus: ClaudeTool = withModel("claude-opus-4-7")

  def withConfig(newConfig: LlmConfig): ClaudeTool =
    copy(config = newConfig)

  def withSystemPrompt(prompt: String): ClaudeTool =
    copy(config = config.copy(systemPrompt = Some(prompt)))

  def withName(newName: String): ClaudeTool = copy(name = newName)

  val autonomous: AutonomousTextCall[Backend.ClaudeCode.type] =
    new AutonomousTextCall[Backend.ClaudeCode.type]:
      def run(
          prompt: String,
          callConfig: LlmConfig = LlmConfig.default
      ): String =
        runHeadless(prompt, callConfig, resume = None).output

      def startSession(
          prompt: String,
          callConfig: LlmConfig = LlmConfig.default
      ): (SessionId[Backend.ClaudeCode.type], String) =
        val result = runHeadless(prompt, callConfig, resume = None)
        (result.sessionId, result.output)

      def continueSession(
          sessionId: SessionId[Backend.ClaudeCode.type],
          prompt: String,
          callConfig: LlmConfig = LlmConfig.default
      ): String =
        runHeadless(prompt, callConfig, resume = Some(sessionId)).output

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    new DefaultLlmCall[Backend.ClaudeCode.type, O](
      backend,
      effectiveConfig,
      prompts,
      workDir,
      events,
      interaction,
      agentName = name
    )

  private def withModel(model: String): ClaudeTool =
    copy(config = config.copy(model = Some(model)))

  private def copy(
      backend: LlmBackend[Backend.ClaudeCode.type] = backend,
      config: LlmConfig = config,
      prompts: Prompts = prompts,
      workDir: os.Path = workDir,
      events: OrcaListener = events,
      interaction: Interaction = interaction,
      name: String = name
  ): DefaultClaudeTool =
    new DefaultClaudeTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )

  private def effectiveConfig(callConfig: LlmConfig): LlmConfig =
    // Call-level config overrides tool-level values where the call explicitly
    // set them. Detection is reference-based: a caller who omitted the arg
    // receives the shared LlmConfig.default singleton.
    if callConfig eq LlmConfig.default then config else callConfig

  /** One headless turn — handles the resume/no-resume split and the TokensUsed
    * emission so the `autonomous` methods stay one-liners.
    */
  private def runHeadless(
      prompt: String,
      callConfig: LlmConfig,
      resume: Option[SessionId[Backend.ClaudeCode.type]]
  ): orca.LlmResult[Backend.ClaudeCode.type] =
    val effective = effectiveConfig(callConfig)
    val result = resume match
      case Some(sid) =>
        backend.continueHeadless(sid, prompt, effective, workDir)
      case None => backend.runHeadless(prompt, effective, workDir)
    emitTokens(effective, result)
    result

  private def emitTokens(
      effective: LlmConfig,
      result: orca.LlmResult[Backend.ClaudeCode.type]
  ): Unit =
    // `agent` is always the tool's name; `model` prefers the model the
    // response actually reports (most precise) and falls back to whatever
    // the caller pinned in config. Stays None when neither is known.
    val model = result.model.orElse(effective.model)
    events.onEvent(OrcaEvent.TokensUsed(name, model, result.usage))
