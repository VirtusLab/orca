package orca.backend.codex

import orca.agents.Model

/** The codex model tiers orca pins by name. */
private[codex] object CodexModels:

  /** The strong default model that bare `codex` pins; `mini` opts down.
    *
    * Pinning is what makes a default codex turn priceable at all: `codex exec
    * --json` names no model anywhere on its stream (probed 2026-08-08,
    * codex-cli 0.145.0 — `thread.started` carries `thread_id` alone, with and
    * without `-m`), so an unpinned turn lands under `(unknown)`. This id is
    * OpenAI's recommended Codex model; with ChatGPT sign-in, codex-cli 0.161.0
    * runs it (0.156.1 gets a 400); older CLIs need an upgrade or
    * `codex.withModel(...)`.
    */
  val Sol: Model = Model("gpt-6.1-sol")

  /** The cheap-and-fast tier. Older codex versions may not offer it, in which
    * case callers pin the right id with `codex.withModel(Model("..."))`.
    */
  val Luna: Model = Model("gpt-6-luna")
