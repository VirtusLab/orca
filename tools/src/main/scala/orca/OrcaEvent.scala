package orca

enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage (no
    * completion) nor a stream-of-text (no continuation). Tools emit these for
    * discrete progress: "switched to branch X", "discarded N issues", etc.
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
    * the verbatim text the agent produced (typically JSON); `summary` is the
    * `Announce[O]`-derived human-readable form when an instance is configured.
    */
  case StructuredResult(raw: String, summary: Option[String])
  case Error(message: String)

trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit

object OrcaListener:
  /** Drops every event. The default for tools that may run without a wired-up
    * dispatcher (unit tests, lightweight scripts).
    */
  val noop: OrcaListener = (_: OrcaEvent) => ()
