package orca.backend

import orca.agents.AgentConfig

/** CLI-flag helpers shared across the backend arg builders (`ClaudeArgs`,
  * `CodexArgs`, `PiArgs`). Each helper renders a single field as a `Seq`
  * suitable for concatenation into a backend's argv. Empty `Seq` when the field
  * is absent, so callers don't have to special-case `None`.
  */
private[orca] object CliArgs:

  /** Render an optional value as its two-token flag `Seq(name, render(value))`,
    * or an empty `Seq` for `None` — the `opt.toSeq.flatMap(v => Seq(name, …))`
    * idiom the arg builders repeat for every optional flag, in one place.
    */
  def flag[A](name: String, opt: Option[A])(render: A => String): Seq[String] =
    opt.toSeq.flatMap(v => Seq(name, render(v)))

  /** `--model <name>` when `config.model` is set, empty otherwise. Both
    * supported backends spell the flag the same way; if a future backend
    * differs, render the model name elsewhere and don't use this helper.
    */
  def modelArgs(config: AgentConfig): Seq[String] =
    flag("--model", config.model)(_.name)
