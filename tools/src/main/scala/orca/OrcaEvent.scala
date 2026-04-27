package orca

enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage
    * (no completion) nor a stream-of-text (no continuation). Tools
    * emit these for discrete progress: "switched to branch X",
    * "discarded N issues", etc. The renderer prints one line with
    * the same `▶` glyph stages use, but never a closing `✔`.
    */
  case Step(message: String)
  /** Token usage for a single LLM call. `model` is always a real,
    * non-empty identifier — when the caller didn't pin a specific
    * model via `LlmConfig.model`, the emitter substitutes the owning
    * tool's `LlmTool.name` ("claude", "codex"). Listeners and trackers
    * can key on it directly without an `Option`/unknown fallback.
    */
  case TokensUsed(model: String, usage: Usage)
  case Error(message: String)

trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit
