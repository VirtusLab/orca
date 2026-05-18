package orca.tools.codex

import orca.{
  Backend,
  CodexTool,
  Interaction,
  LlmBackend,
  LlmConfig,
  OrcaListener,
  Prompts
}
import orca.io.AbstractDefaultLlmTool

/** Default [[CodexTool]] implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[AbstractDefaultLlmTool]] and only adds the
  * Codex-specific `mini` model accessor.
  */
class DefaultCodexTool(
    backend: LlmBackend[Backend.Codex.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends AbstractDefaultLlmTool[Backend.Codex.type, CodexTool](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with CodexTool:

  /** Pin the cheap-and-fast model variant. The literal model id matches what's
    * available in the installed `codex-cli` (gpt-5.4-mini in 0.125.0); newer
    * codex versions may rename, in which case callers override via
    * `withConfig(LlmConfig(model = Some("..."))`.
    */
  def mini: CodexTool = withModel("gpt-5.4-mini")

  protected def copyTool(
      config: LlmConfig = config,
      name: String = name
  ): CodexTool =
    new DefaultCodexTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )
