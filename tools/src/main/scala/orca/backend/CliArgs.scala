package orca.backend

import orca.agents.AgentConfig

/** CLI-flag helpers shared between backend arg builders (`ClaudeArgs`,
  * `CodexArgs`). Each helper renders a single `AgentConfig` field as a `Seq`
  * suitable for concatenation into a backend's argv. Empty `Seq` when the field
  * is absent, so callers don't have to special-case `None`.
  */
private[orca] object CliArgs:

  /** `--model <name>` when `config.model` is set, empty otherwise. Both
    * supported backends spell the flag the same way; if a future backend
    * differs, render the model name elsewhere and don't use this helper.
    */
  def modelArgs(config: AgentConfig): Seq[String] =
    config.model.toSeq.flatMap(m => Seq("--model", m.name))
