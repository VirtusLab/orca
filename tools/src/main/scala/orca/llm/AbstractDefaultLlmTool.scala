package orca.llm

import orca.backend.{Interaction, LlmBackend, LlmResult}
import orca.events.{OrcaEvent, OrcaListener}

/** Skeleton shared by Claude and Codex's default tools — and by any future
  * backend that follows the same `LlmBackend` contract. Centralises the
  * autonomous-text path (which is otherwise pure delegation to
  * `backend.runAutonomous` / `continueAutonomous` plus `TokensUsed` emission),
  * the `resultAs[O]` factory, and the `withConfig` / `withSystemPrompt` /
  * `withName` builders.
  *
  * Concrete subclasses provide:
  *   - the `Self` type bound (their own `LlmTool` subtype) so the builders
  *     return the concrete type;
  *   - a `copyTool` factory that knows the subclass-specific extra params (e.g.
  *     claude has no extra params; a hypothetical backend that needed more
  *     config would override `copyTool` to thread them through);
  *   - the model accessors (`haiku`/`sonnet`/`opus`, `mini`, …) — these are
  *     backend-specific and stay on the subclass.
  */
abstract class AbstractDefaultLlmTool[B <: BackendTag, Self <: LlmTool[B]](
    backend: LlmBackend[B],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction
) extends LlmTool[B]:

  /** Build a sibling instance with the supplied overrides. Concrete subclasses
    * call their own constructor with subclass-specific extra parameters
    * preserved. Used by `withConfig`, `withSystemPrompt`, `withName`, and the
    * model-pinning accessors.
    */
  protected def copyTool(
      config: LlmConfig = config,
      name: String = name
  ): Self

  def withConfig(newConfig: LlmConfig): Self = copyTool(config = newConfig)
  def withSystemPrompt(prompt: String): Self =
    copyTool(config = config.copy(systemPrompt = Some(prompt)))
  def withName(newName: String): Self = copyTool(name = newName)
  def withReadOnly: Self =
    copyTool(config = config.copy(readOnly = true))

  /** Pin the underlying CLI's `--model` flag for subsequent calls. Subclasses
    * expose backend-specific accessors (`haiku`/`sonnet`/`opus`, `mini`) on top
    * of this.
    */
  protected def withModel(model: Model): Self =
    copyTool(config = config.copy(model = Some(model)))

  val autonomous: AutonomousTextCall[B] = new AutonomousTextCall[B]:
    def run(prompt: String, callConfig: LlmConfig = LlmConfig.default): String =
      runAutonomous(prompt, callConfig, resume = None).output

    def startSession(
        prompt: String,
        callConfig: LlmConfig = LlmConfig.default
    ): (SessionId[B], String) =
      val result = runAutonomous(prompt, callConfig, resume = None)
      (result.sessionId, result.output)

    def continueSession(
        sessionId: SessionId[B],
        prompt: String,
        callConfig: LlmConfig = LlmConfig.default
    ): String =
      runAutonomous(prompt, callConfig, resume = Some(sessionId)).output

  def resultAs[O: JsonData: Announce]: LlmCall[B, O] =
    new DefaultLlmCall[B, O](
      backend,
      effectiveConfig,
      prompts,
      workDir,
      events,
      interaction,
      agentName = name
    )

  /** One autonomous turn — handles the resume/no-resume split and the
    * `TokensUsed` emission so the `autonomous` methods stay one-liners.
    * `events` flows into the backend so the drain can surface per-tool-use and
    * per-message progress as the subprocess runs.
    */
  private def runAutonomous(
      prompt: String,
      callConfig: LlmConfig,
      resume: Option[SessionId[B]]
  ): LlmResult[B] =
    val effective = effectiveConfig(callConfig)
    val result = resume match
      case Some(sid) =>
        backend.continueAutonomous(sid, prompt, effective, workDir, events)
      case None => backend.runAutonomous(prompt, effective, workDir, events)
    emitTokens(effective, result)
    result

  /** `agent` axis is always this tool's name; `model` prefers the
    * response-reported model (most precise) and falls back to whatever the
    * caller pinned in config. Stays None when neither is known.
    */
  private def emitTokens(effective: LlmConfig, result: LlmResult[B]): Unit =
    val model = result.model.orElse(effective.model)
    events.onEvent(OrcaEvent.TokensUsed(name, model, result.usage))

  /** If the caller omitted the per-call `config` arg they get the shared
    * `LlmConfig.default` singleton; in that case fall back to the tool-level
    * config. Any explicit `LlmConfig` from the call site wholly replaces the
    * tool-level one — no per-field merge.
    */
  private def effectiveConfig(callConfig: LlmConfig): LlmConfig =
    if callConfig eq LlmConfig.default then config else callConfig
