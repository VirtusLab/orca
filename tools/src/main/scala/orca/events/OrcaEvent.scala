package orca.events

import orca.agents.Model

/** Flow-level event fanned out to every registered [[OrcaListener]]. Covers
  * stage transitions, tool invocations, token usage, structured results, and
  * errors — anything observers like the status bar, cost tracker, or external
  * log shippers want to see across the entire flow.
  *
  * Events exist for observability only — progress display, cost accounting,
  * trace logging, log shipping. They are NOT a control-flow mechanism: no
  * runtime decision reads them back (the production listeners render,
  * accumulate, or log — nothing more), listeners return `Unit`, and the
  * dispatcher isolates the emitter from listener failures (see
  * [[OrcaListener]]), so an observer cannot alter the flow's outcome. Anything
  * that must drive logic travels through return values or exceptions, never
  * through this event stream.
  *
  * Distinct from [[orca.backend.ConversationEvent]], which is scoped to a
  * single live LLM conversation: assistant text deltas, tool-approval prompts,
  * etc., consumed only by the [[orca.backend.Interaction]] that drives that
  * conversation. Conversation events stay between driver and channel;
  * `OrcaEvent`s fan out to all listeners.
  */
enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String)
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage (no
    * completion) nor a stream-of-text (no continuation). Tools emit these for
    * discrete progress: "switched to branch X", "discarded N issues", etc.
    */
  case Step(message: String)

  /** Token usage for a single LLM call, attributed along three independent
    * axes:
    *
    *   - `agent` is the [[Agent.name]] that issued the call — always the
    *     agent's bare identity (`claude`, `codex`, `performance`, …), never a
    *     display-prefixed copy: renaming a reviewer's agent for cost grouping
    *     is what `role` (below) replaced.
    *   - `model` is the concrete model the backend reports it actually served
    *     the call with. `None` when the response didn't carry it and no model
    *     was pinned via `AgentConfig.model`. Coarser groupings (model family /
    *     provider — claude, gpt, gemini) are deliberately NOT a fourth axis:
    *     they are derivable from this id at display time, whereas emission
    *     sites would have to guess them for provider-agnostic backends
    *     (opencode routes many providers, and orca doesn't normalise model ids
    *     — see [[orca.agents.Model]]).
    *   - `role` is the [[Agent.role]] tag, set at the emission edge (e.g. the
    *     review loop's `Some("reviewer")`, via `withRole`). `None` for an
    *     ordinary call. Purely a grouping/display hint — never parsed back out
    *     of `agent`.
    *
    * `CostTracker` summarises usage along all three axes — by-agent shows where
    * the tokens were spent, by-model shows which models cost what, and by-role
    * optionally subtotals e.g. all reviewer spend together.
    */
  case TokensUsed(
      agent: String,
      model: Option[Model],
      usage: Usage,
      role: Option[String] = None
  )

  /** The agent's final structured payload, after parsing succeeded. `raw` is
    * the verbatim text the agent produced (typically JSON); `summary` is the
    * `Announce[O]`-derived human-readable form, tri-state:
    *
    *   - `Some(text)` — a specific `Announce[O]` produced a summary; renderers
    *     show it;
    *   - `Some("")` — a specific `Announce[O]` deliberately says nothing (the
    *     call site narrates the outcome itself, e.g. the review loop's
    *     per-reviewer lines); renderers show nothing;
    *   - `None` — no specific `Announce[O]` exists; renderers fall back to the
    *     raw payload so the result stays visible.
    */
  case StructuredResult(raw: String, summary: Option[String])

  /** The human-readable input that was sent to the agent at the start of an
    * autonomous call. Fires once per call (before [[TokensUsed]] /
    * [[StructuredResult]] / [[AssistantMessage]]), so the user sees what the
    * agent is being asked to do. Interactive calls surface this through the
    * conversation renderer's own user-message line and do not emit this event.
    * The terminal listener renders it as a `▸` line, truncated to one line —
    * full text is available to non-terminal listeners.
    */
  case UserPrompt(text: String)

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
  * sinks must ensure those sinks tolerate concurrent calls too. Implementations
  * should not throw from `onEvent`, but if they do, the dispatcher logs the
  * failure at ERROR, announces it on stderr, and quarantines the listener —
  * permanently excluded from all further dispatch for the rest of the run,
  * since its internal state is presumed unrecoverable. A listener failure is
  * never surfaced to the emitting flow, and deliberately does NOT end the run:
  * quarantine is per-listener (every other listener still sees every event),
  * events are observability-only (see [[OrcaEvent]] — an observer must not be
  * able to abort the run it merely watches), and `emit` being total is
  * load-bearing: failure-teardown paths emit from `catch` blocks where a
  * listener throw would mask the original failure the user needs to see (see
  * `FlowLifecycle`).
  */
trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit

object OrcaListener:
  /** Drops every event. The default for tools that may run without a wired-up
    * dispatcher (unit tests, lightweight scripts).
    */
  val noop: OrcaListener = (_: OrcaEvent) => ()
