package orca.tools.codex

import orca.agents.Model

/** The codex model tiers orca pins by name. */
private[orca] object CodexModels:

  /** The strong default model that bare `codex` pins; `mini` opts down.
    *
    * Pinning is what makes a default codex turn priceable at all: `codex exec
    * --json` names no model anywhere on its stream (probed 2026-08-08,
    * codex-cli 0.145.0 — `thread.started` carries `thread_id` alone, with and
    * without `-m`), so an unpinned turn lands under `(unknown)`. This id is
    * OpenAI's recommended Codex model; codex-cli 0.155.1 offers it (0.145.0
    * lacks it); older CLIs need an upgrade or `codex.withModel(...)`.
    */
  val Sol: Model = Model("gpt-6-sol")

  /** The cheap-and-fast tier. Older codex versions may not offer it, in which
    * case callers pin the right id with `codex.withModel(Model("..."))`.
    */
  val Luna: Model = Model("gpt-6-luna")
