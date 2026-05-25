package orca.events

import orca.llm.Model

/** Flow-level event fanned out to every registered [[OrcaListener]]. Covers
  * stage transitions, tool invocations, token usage, structured results, and
  * errors — anything observers like the status bar, cost tracker, or external
  * log shippers want to see across the entire flow.
  *
  * Distinct from [[orca.backend.ConversationEvent]], which is scoped to a
  * single live LLM conversation: assistant text deltas, tool-approval prompts,
  * etc., consumed only by the [[orca.backend.Interaction]] that drives that
  * conversation. Conversation events stay between driver and channel;
  * `OrcaEvent`s fan out to all listeners.
  */
enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage (no
    * completion) nor a stream-of-text (no continuation). Tools emit these for
    * discrete progress: "switched to branch X", "discarded N issues", etc.
    */
  case Step(message: String)

  /** Token usage for a single LLM call, attributed along two independent axes:
    *
    *   - `agent` is the [[LlmTool.name]] that issued the call. For reviewer
    *     agents this carries the reviewer identity (`abstraction`,
    *     `performance`, …); for the main coding agent it's `claude` / `codex`
    *     (or whatever the script renamed it to via `withName`).
    *   - `model` is the concrete model the backend reports it actually served
    *     the call with. `None` when the response didn't carry it and no model
    *     was pinned via `LlmConfig.model`.
    *
    * `CostTracker` summarises usage along both axes — by-agent shows where the
    * tokens were spent, by-model shows which models cost what.
    */
  case TokensUsed(agent: String, model: Option[Model], usage: Usage)

  /** The agent's final structured payload, after parsing succeeded. `raw` is
    * the verbatim text the agent produced (typically JSON); `summary` is the
    * `Announce[O]`-derived human-readable form when an instance is configured.
    */
  case StructuredResult(raw: String, summary: Option[String])

  /** A turn of free-form prose from the agent. The autonomous drain emits one
    * per [[ConversationEvent.AssistantTurnEnd]] so the user sees what the agent
    * is doing without the interactive renderer attached. The terminal listener
    * renders it as a `●` line, truncated to one line — full text is available
    * to non-terminal listeners.
    */
  case AssistantMessage(text: String)

  case Error(message: String)

/** Sink for [[OrcaEvent]]s.
  *
  * **Implementations MUST be thread-safe.** `onEvent` is called from parallel
  * agent forks (e.g. concurrent reviewers via `reviewAndFixLoop`, concurrent
  * LLM calls via `ox.par`), often without any external synchronization on the
  * caller side. Listeners that mutate shared state must do so atomically
  * (`AtomicReference`, `synchronized`, etc.); listeners that delegate to other
  * sinks must ensure those sinks tolerate concurrent calls too. Throwing from
  * `onEvent` propagates up to the caller — return cleanly or swallow as the
  * concrete listener sees fit.
  */
trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit

object OrcaListener:
  /** Drops every event. The default for tools that may run without a wired-up
    * dispatcher (unit tests, lightweight scripts).
    */
  val noop: OrcaListener = (_: OrcaEvent) => ()
