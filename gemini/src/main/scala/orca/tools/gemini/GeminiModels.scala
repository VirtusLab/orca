package orca.tools.gemini

import orca.agents.Model

/** The Gemini model tiers orca pins by name. */
private[orca] object GeminiModels:

  /** The strong default model that bare `gemini` pins; `flash` opts down. The
    * only 3.x Pro, still a preview; Google may rename it on GA — override via
    * `withConfig` if so.
    */
  val Pro: Model = Model("gemini-3.1-pro-preview")

  /** The cheap-and-fast tier. Newer `gemini` CLI versions may rename the id —
    * callers override via `withModel`.
    */
  val Flash: Model = Model("gemini-3.8-flash")
