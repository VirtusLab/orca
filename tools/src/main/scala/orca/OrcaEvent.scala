package orca

enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage (no
    * completion) nor a stream-of-text (no continuation). Tools emit these for
    * discrete progress: "switched to branch X", "discarded N issues", etc. The
    * renderer prints one line with the same `▶` glyph stages use, but never a
    * closing `✔`.
    */
  case Step(message: String)

  /** Token usage for a single LLM call. `model` is always a real, non-empty
    * identifier — when the caller didn't pin a specific model via
    * `LlmConfig.model`, the emitter substitutes the owning tool's
    * `LlmTool.name` ("claude", "codex"). Listeners and trackers can key on it
    * directly without an `Option`/unknown fallback.
    */
  case TokensUsed(model: String, usage: Usage)

  /** The agent's final structured payload, after parsing succeeded. `raw` is
    * the verbatim text the agent produced (typically the JSON the parser saw);
    * `summary` is the `Announce[O]`-derived human-readable form, or `None` if
    * no specific instance is provided (the catch-all default returns an empty
    * string, which the library normalises to `None`).
    *
    * Listeners decide what to render: a terminal channel typically prints
    * `summary` if present and falls back to `raw` otherwise, since the
    * conversation's assistant-text stream for the structured turn is suppressed
    * elsewhere. Other listeners (a Slack adapter, a structured log) can carry
    * both fields through unchanged.
    */
  case StructuredResult(raw: String, summary: Option[String])
  case Error(message: String)

trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit
