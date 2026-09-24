package orca.tools.claude

import orca.agents.Model

/** The Claude model tiers orca pins by name. */
private[orca] object ClaudeModels:
  /** The cheap tier. Spelled out rather than the `haiku` alias, which the CLI
    * mis-resolved under plan mode — see `ClaudeArgs.modelArgs`. That rewrite
    * covers a `claude:haiku` pin too, so neither spelling tracks a new haiku
    * generation: this constant is the one place to bump when one ships.
    */
  val Haiku: Model = Model("claude-haiku-4-5")

  val Sonnet: Model = Model("claude-sonnet-5")

  /** The default coding model: Opus with the 1M-token context window, via the
    * `[1m]` model-alias suffix. A coder session runs many model requests over
    * whole-branch diffs, so 1M keeps it from overflowing ("Prompt is too
    * long"). Cheaper one-shot calls go through `claude.sonnet` /
    * `claude.haiku`.
    */
  val Opus1M: Model = Model("claude-opus-5-5[1m]")

  /** Fable: the most capable tier, above Opus. Opt in via `claude.fable` for
    * the hardest one-shots; the coder stays on Opus (the default) for cost. 1M
    * context at standard pricing.
    */
  val Fable: Model = Model("claude-fable-5-1")
